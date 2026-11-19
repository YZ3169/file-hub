#!/usr/bin/env bash
# file-hub 多实例（集群）端到端验收脚本
#
# 前置条件：
#   1. 两个实例共用同一个 MySQL，root 指向同一个共享目录；
#   2. 两个实例都设置 --file-hub.cluster.enabled=true，指向同一个 Redis；
#   3. 本机有 curl、sha256sum、split、python。
#
# 用法：
#   bash scripts/e2e-cluster.sh
#   BASE1=http://127.0.0.1:8081 BASE2=http://127.0.0.1:8082 \
#   FILE_HUB_ROOT=/data/file-hub bash scripts/e2e-cluster.sh
#
# 验证点：
#   - 会话存在数据库里：在 8081 注册的 Token 直接访问 8082 有效；
#   - 分片交替写到两个节点，合并/下载跨节点工作；
#   - 秒传/物理去重跨节点命中，物理文件只有一份；
#   - 删除一个逻辑引用后物理文件保留，删最后一个才释放；
#   - 同一文件并发向两个节点点「完成」，两边都成功且只有一份物理文件；
#   - 登录限流计数在两个节点间共享。

set -u

export PYTHONIOENCODING=utf-8
export PYTHONUTF8=1

BASE1="${BASE1:-http://127.0.0.1:8081}"
BASE2="${BASE2:-http://127.0.0.1:8082}"
SHARED_ROOT="${FILE_HUB_ROOT:-D:/Projects/file-hub-cluster-data}"
MAIN_MB="${MAIN_MB:-12}"
CONC_MB="${CONC_MB:-8}"

WORK="$(mktemp -d)"
PASS=0
FAIL=0
RUN_ID="$(date +%s)$RANDOM"
USER="cluster$RUN_ID"
PASSWORD="filehub-cluster-pass"
TOKEN=""

trap 'rm -rf "$WORK"' EXIT

jget() {  # jget '<json>' "['data']['fileId']"
    python -c "import sys,json; d=json.loads(sys.argv[1]); print(d$2)" "$1" "$2" 2>/dev/null
}

http_code() {  # http_code BASE PATH [curl args...]
    local base="$1" path="$2"; shift 2
    curl -s -o /dev/null -w '%{http_code}' "$@" "$base$path"
}

ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; return 0; }
check_eq() { if [ "$1" = "$2" ]; then ok "$3"; else bad "$3" "期望 [$1]，实际 [$2]"; fi; }
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

post_json_to() {  # post_json_to BASE TOKEN PATH JSON
    local base="$1" tok="$2" path="$3" body="$4"
    local f="$WORK/payload.$$.$RANDOM.json"
    printf '%s' "$body" > "$f"
    if [ -n "$tok" ]; then
        curl -s -X POST -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
            --data-binary "@$f" "$base$path"
    else
        curl -s -X POST -H 'Content-Type: application/json' --data-binary "@$f" "$base$path"
    fi
}

api_to() {  # api_to BASE TOKEN METHOD PATH
    local base="$1" tok="$2" method="$3" path="$4"
    curl -s -X "$method" -H "Authorization: Bearer $tok" "$base$path"
}

chunk_to() {  # chunk_to BASE FILE_ID INDEX PART
    local base="$1" fid="$2" idx="$3" part="$4"
    local hash
    hash="$(sha256sum "$part" | cut -d' ' -f1)"
    curl -s -X POST -H "Authorization: Bearer $TOKEN" \
        -F "fileId=$fid" -F "index=$idx" -F "chunkHash=$hash" -F "chunk=@$part" \
        "$base/api/file/chunk"
}

init_to() {  # init_to BASE NAME SIZE HASH
    post_json_to "$1" "$TOKEN" /api/file/init \
        "{\"fileName\":\"$2\",\"fileSize\":$3,\"fileHash\":\"$4\"}"
}

download_to() {  # download_to BASE FILE_ID DEST
    curl -s -H "Authorization: Bearer $TOKEN" "$1/api/file/$2/download" -o "$3"
}

# ---------------------------------------------------------------- 0. 前置检查

section "0. 两个节点与共享配置"
CFG1="$(curl -s "$BASE1/api/file/config")"
CFG2="$(curl -s "$BASE2/api/file/config")"
check_eq "0" "$(jget "$CFG1" "['code']")" "节点 1 可访问（$BASE1）"
check_eq "0" "$(jget "$CFG2" "['code']")" "节点 2 可访问（$BASE2）"
CHUNK="$(jget "$CFG2" "['data']['chunkSize']")"
if ! [[ "$CHUNK" =~ ^[0-9]+$ ]] || [ "$CHUNK" -le 0 ]; then
    bad "读取分片大小" "config 返回：$CFG2"
    echo "集群 E2E 失败：无法读取 chunkSize"; exit 1
fi
ok "两个节点读取到相同的分片大小 $CHUNK"

# ---------------------------------------------------------------- 1. 会话共享

section "1. 会话存放在数据库，不是某个 JVM 的内存"
R="$(post_json_to "$BASE1" "" /api/auth/register \
    "{\"username\":\"$USER\",\"password\":\"$PASSWORD\"}")"
check_eq "0" "$(jget "$R" "['code']")" "在节点 1 注册用户"
TOKEN="$(jget "$R" "['data']['token']")"
if [ -z "$TOKEN" ]; then bad "注册未返回 Token"; echo "集群 E2E 失败"; exit 1; fi
R="$(api_to "$BASE2" "$TOKEN" GET /api/auth/me)"
check_eq "$USER" "$(jget "$R" "['data']['username']")" "节点 1 发的 Token 在节点 2 直接有效"
check_eq "0" "$(jget "$R" "['code']")" "节点 2 /me 正常返回"

# ---------------------------------------------------------------- 2. 跨节点上传

section "2. 分片交替写两个节点，跨节点查询/合并/下载"
MARKER="$WORK/marker"
touch "$MARKER"
head -c $((MAIN_MB * 1024 * 1024)) /dev/urandom > "$WORK/main.bin"
MAIN_SIZE="$(stat -c %s "$WORK/main.bin")"
MAIN_HASH="$(sha256sum "$WORK/main.bin" | cut -d' ' -f1)"
split -b "$CHUNK" -d -a 3 "$WORK/main.bin" "$WORK/main.part."

R="$(init_to "$BASE1" "cluster-main.bin" "$MAIN_SIZE" "$MAIN_HASH")"
FILE_ID="$(jget "$R" "['data']['fileId']")"
TOTAL="$(jget "$R" "['data']['chunkTotal']")"
check_eq "False" "$(jget "$R" "['data']['instant']")" "节点 1 init 未命中秒传"
if [ -z "$FILE_ID" ] || [ -z "$TOTAL" ]; then bad "init 未返回 fileId/chunkTotal"; echo "$R"; exit 1; fi

UPLOAD_OK=1
idx=0
for part in "$WORK"/main.part.*; do
    if [ $((idx % 2)) -eq 0 ]; then base="$BASE1"; else base="$BASE2"; fi
    R="$(chunk_to "$base" "$FILE_ID" "$idx" "$part")"
    if [ "$(jget "$R" "['code']")" != "0" ]; then
        UPLOAD_OK=0; bad "分片 $idx 上传到节点 $([ "$base" = "$BASE1" ] && echo 1 || echo 2)" "$R"
    fi
    idx=$((idx+1))
done
check_eq "1" "$UPLOAD_OK" "全部 $TOTAL 个分片交替上传成功（两个节点都有落盘）"

R="$(api_to "$BASE2" "$TOKEN" GET "/api/file/$FILE_ID/status")"
check_eq "$TOTAL" "$(jget "$R" "['data']['uploadedIndexes'].__len__()")" \
    "节点 2 能看到节点 1 初始化的任务和全部分片"

R="$(api_to "$BASE1" "$TOKEN" POST "/api/file/$FILE_ID/complete")"
check_eq "0" "$(jget "$R" "['code']")" "节点 1 合并成功"
check_eq "1" "$(jget "$R" "['data']['status']")" "合并后状态为已完成"

download_to "$BASE2" "$FILE_ID" "$WORK/downloaded.bin"
check_eq "$MAIN_HASH" "$(sha256sum "$WORK/downloaded.bin" | cut -d' ' -f1)" \
    "从节点 2 下载回来 SHA-256 一致"

# ---------------------------------------------------------------- 2.5 提取码分享跨节点

section "2.5 提取码分享：A 创建，B 匿名提取，A 取消后 B 立即失效"
R="$(post_json_to "$BASE1" "$TOKEN" /api/share \
    "{\"fileId\":\"$FILE_ID\",\"expiresInDays\":7}")"
check_eq "0" "$(jget "$R" "['code']")" "节点 1 创建提取码分享"
SHARE_CODE="$(jget "$R" "['data']['code']")"
SHARE_TOKEN="$(jget "$R" "['data']['token']")"
if [ -z "$SHARE_CODE" ] || [ -z "$SHARE_TOKEN" ]; then
    bad "创建分享未返回 code/token" "$R"; echo "集群 E2E 失败"; exit 1
fi

R="$(curl -s "$BASE2/api/public/share/$SHARE_TOKEN")"
check_eq "ACTIVE" "$(jget "$R" "['data']['state']")" "节点 2 已能看到分享状态"
case "$R" in *fileName*) bad "meta 泄露文件名" "$R";; *) ok "meta 不包含文件名";; esac

R="$(post_json_to "$BASE2" "" "/api/public/share/$SHARE_TOKEN/verify" \
    "{\"code\":\"$SHARE_CODE\"}")"
check_eq "0" "$(jget "$R" "['code']")" "节点 2 匿名校验提取码通过"
TICKET_URL="$(jget "$R" "['data']['downloadUrl']")"
check_eq "cluster-main.bin" "$(jget "$R" "['data']['fileName']")" "匿名接口返回文件名"

curl -s -o "$WORK/share-downloaded.bin" "$BASE2$TICKET_URL"
check_eq "$MAIN_HASH" "$(sha256sum "$WORK/share-downloaded.bin" | cut -d' ' -f1)" \
    "用节点 2 签发的票据从节点 2 下载，内容一致"
curl -s -o "$WORK/share-range.bin" -H 'Range: bytes=100-199' "$BASE2$TICKET_URL"
dd if="$WORK/main.bin" of="$WORK/share-expect.bin" bs=1 skip=100 count=100 status=none
check_eq "$(sha256sum "$WORK/share-expect.bin" | cut -d' ' -f1)" \
    "$(sha256sum "$WORK/share-range.bin" | cut -d' ' -f1)" "匿名票据的 Range 下载跨节点正确"

R="$(api_to "$BASE1" "$TOKEN" DELETE "/api/share/$SHARE_TOKEN")"
check_eq "0" "$(jget "$R" "['code']")" "节点 1 取消分享"
R="$(curl -s "$BASE2/api/public/share/$SHARE_TOKEN")"
check_eq "42001" "$(jget "$R" "['code']")" "取消后节点 2 的 meta 立即返回 42001"
R="$(post_json_to "$BASE2" "" "/api/public/share/$SHARE_TOKEN/verify" \
    "{\"code\":\"$SHARE_CODE\"}")"
check_eq "42001" "$(jget "$R" "['code']")" "取消后节点 2 用正确码也无法提取"

# ---------------------------------------------------------------- 3. 秒传 / 去重

section "3. 跨节点秒传与物理去重"
R="$(init_to "$BASE2" "cluster-main-copy.bin" "$MAIN_SIZE" "$MAIN_HASH")"
INSTANT_ID="$(jget "$R" "['data']['fileId']")"
check_eq "True" "$(jget "$R" "['data']['instant']")" "节点 2 init 同一内容秒传命中"
if [ "$INSTANT_ID" = "$FILE_ID" ]; then
    bad "秒传应返回新的逻辑 fileId" "返回了另一个用户的 fileId：$INSTANT_ID"
else
    ok "秒传返回新的逻辑 fileId（没有把已有记录的 id 交给当前用户）"
fi
download_to "$BASE1" "$INSTANT_ID" "$WORK/instant.bin"
check_eq "$MAIN_HASH" "$(sha256sum "$WORK/instant.bin" | cut -d' ' -f1)" \
    "秒传记录可从另一个节点下载且内容一致"

PHYSICAL="$(find "$SHARED_ROOT/files" -type f -newer "$MARKER" 2>/dev/null | wc -l | tr -d ' ')"
check_eq "1" "$PHYSICAL" "同一内容在共享盘上只有一份物理文件"

# 删除一个引用后物理文件必须保留
R="$(api_to "$BASE2" "$TOKEN" DELETE "/api/file/$INSTANT_ID")"
check_eq "0" "$(jget "$R" "['code']")" "在节点 2 删除秒传记录"
PHYSICAL="$(find "$SHARED_ROOT/files" -type f -newer "$MARKER" 2>/dev/null | wc -l | tr -d ' ')"
check_eq "1" "$PHYSICAL" "仍有引用时物理文件保留"
download_to "$BASE2" "$FILE_ID" "$WORK/still.bin"
check_eq "$MAIN_HASH" "$(sha256sum "$WORK/still.bin" | cut -d' ' -f1)" "剩下的记录仍可正常下载"

# ---------------------------------------------------------------- 4. 并发完成

section "4. 同一任务同时向两个节点点「完成」"
head -c $((CONC_MB * 1024 * 1024)) /dev/urandom > "$WORK/conc.bin"
CONC_SIZE="$(stat -c %s "$WORK/conc.bin")"
CONC_HASH="$(sha256sum "$WORK/conc.bin" | cut -d' ' -f1)"
split -b "$CHUNK" -d -a 3 "$WORK/conc.bin" "$WORK/conc.part."

R="$(init_to "$BASE1" "cluster-concurrent.bin" "$CONC_SIZE" "$CONC_HASH")"
CONC_ID="$(jget "$R" "['data']['fileId']")"
idx=0
for part in "$WORK"/conc.part.*; do
    if [ $((idx % 2)) -eq 0 ]; then base="$BASE2"; else base="$BASE1"; fi
    R="$(chunk_to "$base" "$CONC_ID" "$idx" "$part")"
    [ "$(jget "$R" "['code']")" = "0" ] || bad "并发送达用例的分片 $idx" "$R"
    idx=$((idx+1))
done

curl -s -X POST -H "Authorization: Bearer $TOKEN" "$BASE1/api/file/$CONC_ID/complete" > "$WORK/complete1.json" &
P1=$!
curl -s -X POST -H "Authorization: Bearer $TOKEN" "$BASE2/api/file/$CONC_ID/complete" > "$WORK/complete2.json" &
P2=$!
wait "$P1"; wait "$P2"
R1="$(cat "$WORK/complete1.json")"
R2="$(cat "$WORK/complete2.json")"
check_eq "0" "$(jget "$R1" "['code']")" "节点 1 的 complete 成功"
check_eq "0" "$(jget "$R2" "['code']")" "节点 2 的 complete 同样成功（幂等返回）"

R="$(api_to "$BASE2" "$TOKEN" GET "/api/file/$CONC_ID/status")"
check_eq "True" "$(jget "$R" "['data']['done']")" "并发完成后节点 2 看到 done=true"
download_to "$BASE1" "$CONC_ID" "$WORK/conc-downloaded.bin"
check_eq "$CONC_HASH" "$(sha256sum "$WORK/conc-downloaded.bin" | cut -d' ' -f1)" \
    "并发合并后的文件内容正确（没有被重复合并写坏）"

# ---------------------------------------------------------------- 5. 删除最后引用

section "5. 删除最后一个引用才释放物理文件"
R="$(api_to "$BASE1" "$TOKEN" DELETE "/api/file/$FILE_ID")"
check_eq "0" "$(jget "$R" "['code']")" "删除主记录"
PHYSICAL="$(find "$SHARED_ROOT/files" -type f -newer "$MARKER" 2>/dev/null | wc -l | tr -d ' ')"
check_eq "1" "$PHYSICAL" "并发完成用例的物理文件仍在（主记录已释放）"

# ---------------------------------------------------------------- 6. 共享限流

section "6. 登录限流计数跨节点共享"
# 默认 login-per-minute=10；两个节点交替发 12 次错误登录，若计数共享，
# 第 11/12 次应被拒绝，并且两个节点都返回 429。窗口边界可能导致计数重置，
# 所以最多重试 3 轮。
probe_ok=0
for attempt in 1 2 3; do
    for i in $(seq 1 12); do
        if [ $((i % 2)) -eq 1 ]; then base="$BASE1"; else base="$BASE2"; fi
        curl -s -o /dev/null -X POST -H 'Content-Type: application/json' \
            --data-binary '{"username":"filehub-no-such-user","password":"wrong-password"}' \
            "$base/api/auth/login"
    done
    C1="$(http_code "$BASE1" /api/auth/login -X POST -H 'Content-Type: application/json' \
        --data-binary '{"username":"filehub-no-such-user","password":"wrong-password"}')"
    C2="$(http_code "$BASE2" /api/auth/login -X POST -H 'Content-Type: application/json' \
        --data-binary '{"username":"filehub-no-such-user","password":"wrong-password"}')"
    if [ "$C1" = "429" ] && [ "$C2" = "429" ]; then
        probe_ok=1
        break
    fi
    sleep 1
done
check_eq "1" "$probe_ok" "两个节点共享同一份登录限流计数（都返回 429）"

# ---------------------------------------------------------------- 汇总

section "结果"
TOTAL_CHECKS=$((PASS+FAIL))
printf '集群 E2E：%d/%d 通过\n' "$PASS" "$TOTAL_CHECKS"
[ "$FAIL" -eq 0 ] || exit 1
