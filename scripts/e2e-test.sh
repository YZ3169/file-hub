#!/usr/bin/env bash
# file-hub 端到端验收脚本
#
# 真刀真枪跑一遍：造大文件 → 分片上传 → 合并 → 下载回来比对摘要，
# 再把秒传 / 断点续传 / 完整性校验 / 删除引用计数逐条验掉。
#
# 用法：bash scripts/e2e-test.sh [服务地址]
#   默认 http://127.0.0.1:8081
#
# 依赖：curl、sha256sum、split、python（只用来解析 JSON）

set -u

# Windows 上 Python 默认按系统代码页（GBK）写 stdout，中文经它一转就成了乱码，
# 会让"服务端原话是什么"这类断言假红。强制它和脚本一样用 UTF-8。
export PYTHONIOENCODING=utf-8
export PYTHONUTF8=1

BASE="${1:-http://127.0.0.1:8081}"
CHUNK=5242880                                   # 5MB，和 application.yml 保持一致
BIG_MB=120                                      # 主用例文件大小
SMALL_MB=12                                     # 完整性校验用例文件大小
CONCURRENCY=4                                   # 分片上传并发度
# 用来做「磁盘上的分片被改坏」用例；服务端不在本机时用 FILE_HUB_ROOT 指过去
STORAGE_ROOT="${FILE_HUB_ROOT:-D:/Projects/file-hub-data}"

WORK="$(mktemp -d)"
PASS=0
FAIL=0

# 每次运行注册独立用户，避免不同脚本/CI 任务之间互相污染
RUN_ID="$(date +%s)$RANDOM"
USER_A="alice$RUN_ID"
USER_B="bob$RUN_ID"
PASSWORD="filehub-e2e-pass"
TOKEN=""
TOKEN_B=""
TOKEN_ADMIN=""

trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------- 工具函数

jget() {  # jget '<json>' '.data.fileId'
    python -c "import sys,json; d=json.loads(sys.argv[1]); print(d$2)" "$1" "$2" 2>/dev/null
}

ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; return 0; }

check_eq() {  # check_eq 期望 实际 说明
    if [ "$1" = "$2" ]; then ok "$3"; else bad "$3" "期望 [$1]，实际 [$2]"; fi
}

check_contains() {  # check_contains 文本 子串 说明
    case "$1" in *"$2"*) ok "$3";; *) bad "$3" "未包含 [$2]，实际：$(echo "$1" | head -c 200)";; esac
}

# 反向断言：整段响应里都不能出现某个字段名。
# 错误响应的 data 是 null，拿 'data'['field'] 去判断会抛异常（null 没有 __contains__），
# 所以直接看响应原文。
check_not_contains() {  # check_not_contains 文本 子串 说明
    case "$1" in *"$2"*) bad "$3" "不应包含 [$2]，实际：$(echo "$1" | head -c 200)";; *) ok "$3";; esac
}

section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

api_as() {  # api_as TOKEN METHOD PATH [curl args...]
    local tok="$1" method="$2" path="$3"; shift 3
    if [ -n "$tok" ]; then
        curl -s -X "$method" -H "Authorization: Bearer $tok" "$BASE$path" "$@"
    else
        curl -s -X "$method" "$BASE$path" "$@"
    fi
}

api() { api_as "$TOKEN" "$@"; }

# 浏览器下载（这里用 curl 模拟）带 Bearer；票据下载则不带
dl() {  # dl [curl args...] URL
    if [ -n "$TOKEN" ]; then curl -s -H "Authorization: Bearer $TOKEN" "$@"; else curl -s "$@"; fi
}

# 发 JSON 一律走文件，不用 `-d '{...}'` 内联。
# 原因：Windows 上 curl.exe 是原生程序，命令行参数会被按系统 ANSI 代码页（本机是 GBK）转换，
# 而 JSON 必须是 UTF-8 —— 内联中文文件名会让服务端报 "Invalid UTF-8 start byte 0xb2"。
# 写进文件再 --data-binary @ 读，是字节透传，不受 argv 编码影响。
post_json_as() {  # post_json_as TOKEN PATH JSON
    local tok="$1" path="$2" body="$3"
    local f="$WORK/payload.$$.$RANDOM.json"
    printf '%s' "$body" > "$f"
    local out
    if [ -n "$tok" ]; then
        out="$(curl -s -X POST -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' \
            --data-binary "@$f" "$BASE$path")"
    else
        out="$(curl -s -X POST -H 'Content-Type: application/json' --data-binary "@$f" "$BASE$path")"
    fi
    rm -f "$f"
    printf '%s' "$out"
}

post_json() { post_json_as "$TOKEN" "$@"; }

# ---------------------------------------------------------------- 前置检查

section "0. 服务可达性"
CONFIG="$(api GET /api/file/config)"
check_contains "$CONFIG" '"code":0' "GET /api/file/config 返回成功"
CFG_CHUNK="$(jget "$CONFIG" "['data']['chunkSize']")"
check_eq "$CHUNK" "$CFG_CHUNK" "服务端分片大小与脚本一致"
[ "$FAIL" -gt 0 ] && { echo "服务没起来，后面的用例没意义，先退出"; exit 1; }

# ---------------------------------------------------------------- 0.5 认证

section "0.5 注册、登录、限流与鉴权"

# 受保护接口没有 Token 时必须 401，而不是返回数据
CODE="$(curl -s -o "$WORK/anon.json" -w '%{http_code}' "$BASE/api/file/list")"
check_eq "401" "$CODE" "未登录访问 /api/file/list 返回 HTTP 401"
check_eq "40001" "$(jget "$(cat "$WORK/anon.json")" "['code']")" "401 响应仍是统一的 Result 结构"

R="$(post_json_as "" /api/auth/register "{\"username\":\"$USER_A\",\"password\":\"$PASSWORD\"}")"
TOKEN="$(jget "$R" "['data']['token']")"
check_eq "0" "$(jget "$R" "['code']")" "用户 A 注册成功"
check_eq "$USER_A" "$(jget "$R" "['data']['user']['username']")" "注册响应带用户信息"
echo "  ↳ 用户 A = $USER_A（每次运行随机，避免数据串台）"

R="$(post_json_as "" /api/auth/register "{\"username\":\"$USER_B\",\"password\":\"$PASSWORD\"}")"
TOKEN_B="$(jget "$R" "['data']['token']")"
check_eq "0" "$(jget "$R" "['code']")" "用户 B 注册成功"

# 密码错误：HTTP 401 + 40004，不能区分「用户不存在」和「密码错」
CODE="$(curl -s -o "$WORK/wrong.json" -w '%{http_code}' -X POST \
    -H 'Content-Type: application/json' \
    --data-binary "$(printf '{"username":"%s","password":"wrong-password"}' "$USER_A")" \
    "$BASE/api/auth/login")"
check_eq "401" "$CODE" "错误密码登录返回 HTTP 401"
check_eq "40004" "$(jget "$(cat "$WORK/wrong.json")" "['code']")" "错误密码返回 40004"

R="$(post_json_as "" /api/auth/login "{\"username\":\"$USER_A\",\"password\":\"$PASSWORD\"}")"
TOKEN="$(jget "$R" "['data']['token']")"
check_eq "0" "$(jget "$R" "['code']")" "用户 A 用正确密码重新登录"

R="$(api GET /api/auth/me)"
check_eq "$USER_A" "$(jget "$R" "['data']['username']")" "GET /api/auth/me 返回当前用户"

# 退出必须让旧 Token 立即失效
OLD_TOKEN="$TOKEN"
api POST /api/auth/logout > /dev/null
CODE="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $OLD_TOKEN" "$BASE/api/auth/me")"
check_eq "401" "$CODE" "退出后旧 Token 访问 /me 返回 401"

R="$(post_json_as "" /api/auth/login "{\"username\":\"$USER_A\",\"password\":\"$PASSWORD\"}")"
TOKEN="$(jget "$R" "['data']['token']")"
check_eq "0" "$(jget "$R" "['code']")" "重新登录拿回 Token，继续后续用例"

# ---------------------------------------------------------------- 0.9 运维端点

section "0.9 运维端点与请求 ID"
CODE="$(curl -s -o "$WORK/health.json" -w '%{http_code}' "$BASE/actuator/health")"
check_eq "200" "$CODE" "GET /actuator/health 返回 HTTP 200"
check_contains "$(cat "$WORK/health.json")" '"status":"UP"' "健康检查状态为 UP"

CODE="$(curl -s -o "$WORK/metrics-anon.json" -w '%{http_code}' "$BASE/actuator/prometheus")"
check_eq "401" "$CODE" "未登录访问 /actuator/prometheus 返回 401"
check_eq "40001" "$(jget "$(cat "$WORK/metrics-anon.json")" "['code']")" "401 响应仍是统一的 Result 结构"

CODE="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
    "$BASE/actuator/prometheus")"
check_eq "403" "$CODE" "普通用户读取 Prometheus 指标返回 403"

REQ_ID="e2e-rid-$RUN_ID"
curl -s -D "$WORK/rid-headers.txt" -o /dev/null -H "X-Request-Id: $REQ_ID" \
    "$BASE/api/file/config"
RID_HDRS="$(tr -d '\r' < "$WORK/rid-headers.txt")"
check_contains "$RID_HDRS" "X-Request-Id: $REQ_ID" \
    "响应头回传调用方的 X-Request-Id"
check_contains "$RID_HDRS" "X-Content-Type-Options: nosniff" \
    "响应头包含 X-Content-Type-Options: nosniff"
check_contains "$RID_HDRS" "Referrer-Policy: no-referrer" \
    "响应头包含 Referrer-Policy: no-referrer"

# Swagger/OpenAPI 默认受管理员保护（除非显式开启 FILE_HUB_DOCS_PUBLIC=true 或 desktop 关闭）
if curl -s "$BASE/api/file/config" | grep -q '"desktop":true'; then
    ok "桌面版未暴露 OpenAPI 文档（按设计跳过）"
else
    CODE="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v3/api-docs")"
    if [ "$CODE" = "401" ]; then
        ok "未登录访问 /v3/api-docs 返回 401（默认受保护）"
        CODE_USER="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
            "$BASE/v3/api-docs")"
        check_eq "403" "$CODE_USER" "普通用户访问 /v3/api-docs 返回 403"
    else
        R="$(curl -s "$BASE/v3/api-docs")"
        check_contains "$R" '"title":"file-hub API"' "OpenAPI 文档（公开模式）可读取且标题正确"
    fi
fi

# ---------------------------------------------------------------- 1. 全量上传

section "1. 分片上传一个大文件（${BIG_MB}MB）"
head -c $((BIG_MB * 1024 * 1024)) /dev/urandom > "$WORK/big.bin"
BIG_HASH="$(sha256sum "$WORK/big.bin" | cut -d' ' -f1)"
BIG_SIZE="$(stat -c %s "$WORK/big.bin")"
echo "  文件摘要 = $BIG_HASH"
echo "  文件大小 = $BIG_SIZE 字节"

# 1.1 预检应未命中
R="$(api GET "/api/file/check?hash=$BIG_HASH&size=$BIG_SIZE")"
check_eq "False" "$(jget "$R" "['data']['exists']")" "秒传预检未命中（全新文件）"

# 1.2 初始化
R="$(post_json /api/file/init "{\"fileName\":\"压力测试-大文件.bin\",\"fileSize\":$BIG_SIZE,\"fileHash\":\"$BIG_HASH\"}")"
FILE_ID="$(jget "$R" "['data']['fileId']")"
CHUNK_TOTAL="$(jget "$R" "['data']['chunkTotal']")"
INSTANT="$(jget "$R" "['data']['instant']")"
check_eq "False" "$INSTANT" "初始化返回 instant=false"
check_eq "$(( (BIG_SIZE + CHUNK - 1) / CHUNK ))" "$CHUNK_TOTAL" "服务端算出的分片总数正确"
echo "  fileId = $FILE_ID，共 $CHUNK_TOTAL 片"

# 1.3 合并前先点一次「完成」，应被拒绝并报出缺哪几片
R="$(api POST "/api/file/$FILE_ID/complete")"
check_eq "30001" "$(jget "$R" "['code']")" "分片没传齐时合并被拒绝（code=30001）"
echo "  ↳ 服务端原话：$(jget "$R" "['msg']" | head -c 120)"

# 1.4 切片并并发上传
split -b "$CHUNK" -d -a 3 "$WORK/big.bin" "$WORK/big.part."
uploaded=0
i=0
while [ "$i" -lt "$CHUNK_TOTAL" ]; do
    pids=()
    for j in 0 1 2 3; do
        idx=$((i + j))
        [ "$idx" -ge "$CHUNK_TOTAL" ] && continue
        part="$(printf '%s/big.part.%03d' "$WORK" "$idx")"
        part_hash="$(sha256sum "$part" | cut -d' ' -f1)"
        (
            r="$(curl -s -X POST "$BASE/api/file/chunk" \
                    -H "Authorization: Bearer $TOKEN" \
                    -F "fileId=$FILE_ID" -F "index=$idx" \
                    -F "chunkHash=$part_hash" -F "chunk=@$part")"
            echo "$r" | grep -q '"code":0' || echo "分片 $idx 上传失败: $r" >&2
        ) &
        pids+=($!)
    done
    for p in "${pids[@]}"; do wait "$p"; done
    i=$((i + CONCURRENCY))
    uploaded=$((uploaded + ${#pids[@]}))
done
echo "  已上传 $uploaded 片"
check_eq "$CHUNK_TOTAL" "$uploaded" "所有分片都提交了"

# 1.5 重复上传同一个分片，应当幂等（不报错、不产生第二片）
DUP_HASH="$(sha256sum "$WORK/big.part.000" | cut -d' ' -f1)"
R="$(curl -s -X POST "$BASE/api/file/chunk" -H "Authorization: Bearer $TOKEN" \
        -F "fileId=$FILE_ID" -F "index=0" \
        -F "chunkHash=$DUP_HASH" -F "chunk=@$WORK/big.part.000")"
check_eq "0" "$(jget "$R" "['code']")" "重复上传同一分片被幂等处理"

# 1.6 合并
T0=$(date +%s%N)
R="$(api POST "/api/file/$FILE_ID/complete")"
T1=$(date +%s%N)
check_eq "0" "$(jget "$R" "['code']")" "合并成功"
check_eq "1" "$(jget "$R" "['data']['status']")" "合并后状态为已完成"
echo "  ↳ 合并耗时 $(( (T1 - T0) / 1000000 )) ms"

# 1.7 再点一次「完成」，应当幂等返回而不是报错
R="$(api POST "/api/file/$FILE_ID/complete")"
check_eq "0" "$(jget "$R" "['code']")" "重复合并是幂等的"

# 1.8 下载回来比对摘要 —— 这是"文件真的没坏"的唯一硬证据
dl -o "$WORK/out.bin" "$BASE/api/file/$FILE_ID/download"
OUT_HASH="$(sha256sum "$WORK/out.bin" | cut -d' ' -f1)"
check_eq "$BIG_HASH" "$OUT_HASH" "下载回来的文件与原文件 SHA-256 一致"

# 1.9 Range 下载：只取前 100 字节，应返回 206 + Content-Range，且字节完全一致
RANGE_CODE="$(dl -D "$WORK/range.headers" -o "$WORK/range.bin" \
    -H 'Range: bytes=0-99' -w '%{http_code}' "$BASE/api/file/$FILE_ID/download")"
check_eq "206" "$RANGE_CODE" "Range 请求返回 206 Partial Content"
check_contains "$(cat "$WORK/range.headers")" "Content-Range: bytes 0-99/$BIG_SIZE" \
    "响应带正确的 Content-Range"
check_eq "$(head -c 100 "$WORK/big.bin" | sha256sum | cut -d' ' -f1)" \
    "$(sha256sum "$WORK/range.bin" | cut -d' ' -f1)" "Range 返回的前 100 字节与原文件一致"

# 1.10 后缀 Range：最后 100 字节
dl -o "$WORK/tail.bin" -H 'Range: bytes=-100' "$BASE/api/file/$FILE_ID/download"
check_eq "$(tail -c 100 "$WORK/big.bin" | sha256sum | cut -d' ' -f1)" \
    "$(sha256sum "$WORK/tail.bin" | cut -d' ' -f1)" "后缀 Range 取到最后 100 字节"

# 1.11 不可满足的 Range 应返回 416，而不是 200 或 500
RANGE_BAD="$(dl -o /dev/null -w '%{http_code}' \
    -H 'Range: bytes=999999999999-' "$BASE/api/file/$FILE_ID/download")"
check_eq "416" "$RANGE_BAD" "越界 Range 返回 416"

# 1.12 多段 Range：multipart/byteranges，每段字节必须和原文件对应区间一致
MULTI_CODE="$(dl -D "$WORK/multi.headers" -o "$WORK/multi.bin" -w '%{http_code}' \
    -H 'Range: bytes=0-9,1000-1009' "$BASE/api/file/$FILE_ID/download")"
check_eq "206" "$MULTI_CODE" "多段 Range 返回 206"
check_contains "$(cat "$WORK/multi.headers")" "multipart/byteranges; boundary=" \
    "多段 Range 响应类型是 multipart/byteranges"
if python - "$WORK/multi.bin" "$WORK/multi.headers" "$WORK/big.bin" <<'PY'
import re
import sys

body = open(sys.argv[1], 'rb').read()
headers = open(sys.argv[2], 'r', encoding='latin-1').read()
original = open(sys.argv[3], 'rb').read()

m = re.search(r'boundary=([^\s;]+)', headers)
if not m:
    sys.exit('响应头里没有 boundary')
boundary = m.group(1).encode()

spans = []
for part in body.split(b'--' + boundary):
    if b'Content-Range:' not in part:
        continue
    head, _, data = part.partition(b'\r\n\r\n')
    rng = re.search(rb'Content-Range: bytes (\d+)-(\d+)/(\d+)', head)
    if not rng:
        sys.exit('段头缺少 Content-Range')
    start, end = int(rng.group(1)), int(rng.group(2))
    data = data[:-2] if data.endswith(b'\r\n') else data
    if original[start:end + 1] != data:
        sys.exit(f'区间 {start}-{end} 的字节与原文件不一致')
    spans.append((start, end))

if spans != [(0, 9), (1000, 1009)]:
    sys.exit(f'段区间解析错误：{spans}')
PY
then
    ok "多段 Range 的每一段都与原文件字节一致"
else
    bad "多段 Range 内容校验失败"
fi

# 1.13 多段 Range 超过 64MB 总量：明确 416，而不是悄悄回退整文件
TOO_BIG="$(dl -o /dev/null -w '%{http_code}' \
    -H 'Range: bytes=0-33554431,33554432-67108863,67108864-100663295' \
    "$BASE/api/file/$FILE_ID/download")"
check_eq "416" "$TOO_BIG" "多段总量超限返回 416"

# 1.12 统计接口
R="$(api GET /api/file/stats)"
check_eq "0" "$(jget "$R" "['code']")" "GET /api/file/stats 返回成功"
check_contains "$R" '"savedBytes"' "统计接口包含秒传节省字节数"

# ---------------------------------------------------------------- 2. 秒传

section "2. 秒传（内容寻址去重）"
R="$(api GET "/api/file/check?hash=$BIG_HASH&size=$BIG_SIZE")"
check_eq "True" "$(jget "$R" "['data']['exists']")" "预检命中已有文件"

R="$(post_json /api/file/init "{\"fileName\":\"换个名字-同一个文件.bin\",\"fileSize\":$BIG_SIZE,\"fileHash\":\"$BIG_HASH\"}")"
check_eq "True" "$(jget "$R" "['data']['instant']")" "再次 init 直接返回 instant=true"
SECOND_ID="$(jget "$R" "['data']['fileId']")"
echo "  ↳ 秒传得到新的 fileId=$SECOND_ID，一个字节都没传"

# 命中文件应当能正常下载（说明它确实指向了已有的物理文件）
dl -o "$WORK/instant.bin" "$BASE/api/file/$SECOND_ID/download"
check_eq "$BIG_HASH" "$(sha256sum "$WORK/instant.bin" | cut -d' ' -f1)" "秒传文件能下载且内容正确"

# ---------------------------------------------------------------- 2.5 多用户隔离

section "2.5 多用户隔离（B 看不到、改不了 A 的文件）"

R="$(api_as "$TOKEN_B" GET "/api/file/list?size=100")"
check_eq "0" "$(jget "$R" "['data']['total']")" "B 的文件列表为空，看不到 A 的文件"

R="$(api_as "$TOKEN_B" GET "/api/file/check?hash=$BIG_HASH&size=$BIG_SIZE")"
check_eq "False" "$(jget "$R" "['data']['exists']")" "B 的秒传预检不命中 A 的文件"

R="$(api_as "$TOKEN_B" GET "/api/file/$FILE_ID/status")"
check_eq "10005" "$(jget "$R" "['code']")" "B 查 A 的 fileId 状态返回 10005"

R="$(api_as "$TOKEN_B" POST "/api/file/$FILE_ID/complete")"
check_eq "10005" "$(jget "$R" "['code']")" "B 不能合并 A 的上传任务"

R="$(api_as "$TOKEN_B" DELETE "/api/file/$FILE_ID")"
check_eq "10005" "$(jget "$R" "['code']")" "B 不能删除 A 的文件"

R="$(curl -s -H "Authorization: Bearer $TOKEN_B" "$BASE/api/file/$FILE_ID/download")"
check_eq "10005" "$(jget "$R" "['code']")" "B 不能用 Bearer 下载 A 的文件"

# 跨账号不允许仅凭 SHA-256 + 大小免传秒传别人的私有文件：必须走正常上传，合并后才能物理去重
R="$(post_json_as "$TOKEN_B" /api/file/init \
    "{\"fileName\":\"B-不秒传.bin\",\"fileSize\":$BIG_SIZE,\"fileHash\":\"$BIG_HASH\"}")"
B_ID="$(jget "$R" "['data']['fileId']")"
check_eq "False" "$(jget "$R" "['data']['instant']")" "B 仅凭摘要不能免传秒传 A 的私有文件"
R="$(api_as "$TOKEN_B" DELETE "/api/file/$B_ID")"
check_eq "0" "$(jget "$R" "['code']")" "清理 B 的未完成上传任务"
R="$(api_as "$TOKEN_B" GET "/api/file/list?size=100")"
check_eq "0" "$(jget "$R" "['data']['total']")" "B 清理后列表仍为空"

if [ -n "${FILE_HUB_ADMIN_PASSWORD:-}" ]; then
    R="$(post_json_as "" /api/auth/login \
        "{\"username\":\"${FILE_HUB_ADMIN_USER:-admin}\",\"password\":\"$FILE_HUB_ADMIN_PASSWORD\"}")"
    TOKEN_ADMIN="$(jget "$R" "['data']['token']")"
    check_eq "0" "$(jget "$R" "['code']")" "管理员账号可以登录"
    R="$(api_as "$TOKEN_ADMIN" GET "/api/file/list?scope=all&size=100")"
    check_contains "$R" "\"fileId\":\"$FILE_ID\"" "管理员 scope=all 能看到用户 A 的文件"
else
    echo "  ↳ 未设置 FILE_HUB_ADMIN_PASSWORD，跳过管理员可见性用例"
fi

# ---------------------------------------------------------------- 2.6 下载票据

section "2.6 下载票据（浏览器拿不了 Bearer 头）"

R="$(api GET "/api/file/$FILE_ID/download-ticket")"
TICKET_URL="$(jget "$R" "['data']['url']")"
check_eq "0" "$(jget "$R" "['code']")" "登录用户可以申请下载票据"
check_contains "$TICKET_URL" "/download?ticket=" "票据 URL 使用 ticket 查询参数"

curl -s -o "$WORK/ticket.bin" "$BASE$TICKET_URL"
check_eq "$BIG_HASH" "$(sha256sum "$WORK/ticket.bin" | cut -d' ' -f1)" \
         "无 Bearer、仅凭票据即可下载，内容正确"

R="$(curl -s "$BASE/api/file/$FILE_ID/download?ticket=not-a-real-ticket")"
check_eq "40001" "$(jget "$R" "['code']")" "伪造票据被拒绝（40001，未通过鉴权）"

CODE="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/file/$FILE_ID/download")"
check_eq "401" "$CODE" "既无 Bearer 又无票据时下载返回 401"

# ---------------------------------------------------------------- 2.7 提取码分享

section "2.7 提取码分享（链接 + 4 位码，免登录下载）"

R="$(post_json /api/share "{\"fileId\":\"$FILE_ID\",\"expiresInDays\":7}")"
SHARE_TOKEN="$(jget "$R" "['data']['token']")"
SHARE_CODE="$(jget "$R" "['data']['code']")"
check_eq "0" "$(jget "$R" "['code']")" "创建 7 天有效的分享"
check_eq "4" "${#SHARE_CODE}" "提取码是 4 位"
check_contains "$(jget "$R" "['data']['sharePath']")" "#/s/$SHARE_TOKEN" "分享链接指向 #/s/<token>"

R="$(api GET "/api/share/mine?page=1&size=20")"
check_eq "0" "$(jget "$R" "['code']")" "GET /api/share/mine 返回成功"
check_eq "ACTIVE" "$(jget "$R" "['data']['items'][0]['state']")" "我的分享里状态是 ACTIVE"

CODE="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/share/mine")"
check_eq "401" "$CODE" "未登录不能管理分享"

# 匿名只能看状态，不能看到文件名/大小/所有者
R="$(api_as "" GET "/api/public/share/$SHARE_TOKEN")"
check_eq "ACTIVE" "$(jget "$R" "['data']['state']")" "匿名可以查询分享状态"
check_not_contains "$R" "fileName" "匿名 meta 不返回文件名"
check_not_contains "$R" "fileSize" "匿名 meta 不返回文件大小"
check_not_contains "$R" "owner" "匿名 meta 不暴露所有者"

WRONG_CODE="AAAA"
[ "$SHARE_CODE" = "AAAA" ] && WRONG_CODE="BBBB"

R="$(post_json_as "" "/api/public/share/$SHARE_TOKEN/verify" "{\"code\":\"$WRONG_CODE\"}")"
check_eq "42003" "$(jget "$R" "['code']")" "提取码错误返回 42003"
check_not_contains "$R" "fileName" "校验失败不泄露文件名"
check_not_contains "$R" "downloadUrl" "校验失败不签发下载地址"

# 提取码大小写不敏感：小写输入也应当通过
LOWER_CODE="$(echo "$SHARE_CODE" | tr 'A-Z' 'a-z')"
R="$(post_json_as "" "/api/public/share/$SHARE_TOKEN/verify" "{\"code\":\"$LOWER_CODE\"}")"
check_eq "0" "$(jget "$R" "['code']")" "正确提取码（小写）校验通过"
SHARE_URL="$(jget "$R" "['data']['downloadUrl']")"
check_contains "$SHARE_URL" "/download?ticket=" "校验返回的是现有票据下载地址"

# 不带任何 Bearer，仅凭票据下载
curl -s -o "$WORK/share.bin" "$BASE$SHARE_URL"
check_eq "$BIG_HASH" "$(sha256sum "$WORK/share.bin" | cut -d' ' -f1)" \
         "提取人免登录下载，内容与原文件一致"

# 取消后立即失效
R="$(api DELETE "/api/share/$SHARE_TOKEN")"
check_eq "0" "$(jget "$R" "['code']")" "所有者取消分享"
R="$(api_as "" GET "/api/public/share/$SHARE_TOKEN")"
check_eq "42001" "$(jget "$R" "['code']")" "取消后匿名访问变成 42001"
R="$(post_json_as "" "/api/public/share/$SHARE_TOKEN/verify" "{\"code\":\"$SHARE_CODE\"}")"
check_eq "42001" "$(jget "$R" "['code']")" "取消后正确提取码也不能再下载"

# 永久分享 + 连续错码锁定 + 重置提取码解锁
R="$(post_json /api/share "{\"fileId\":\"$FILE_ID\",\"expiresInDays\":0}")"
SHARE2="$(jget "$R" "['data']['token']")"
CODE2="$(jget "$R" "['data']['code']")"
check_eq "0" "$(jget "$R" "['code']")" "创建永久分享"
check_eq "None" "$(jget "$R" "['data']['expiresAt']")" "永久分享的 expiresAt 为空"

WRONG2="AAAA"
[ "$CODE2" = "AAAA" ] && WRONG2="BBBB"
for i in $(seq 1 9); do
    R="$(post_json_as "" "/api/public/share/$SHARE2/verify" "{\"code\":\"$WRONG2\"}")"
done
check_eq "42003" "$(jget "$R" "['code']")" "第 9 次错码仍是 42003"
R="$(post_json_as "" "/api/public/share/$SHARE2/verify" "{\"code\":\"$WRONG2\"}")"
check_eq "42004" "$(jget "$R" "['code']")" "第 10 次错码触发锁定（42004）"
R="$(post_json_as "" "/api/public/share/$SHARE2/verify" "{\"code\":\"$CODE2\"}")"
check_eq "42004" "$(jget "$R" "['code']")" "锁定期间正确码同样被拒绝"

R="$(api POST "/api/share/$SHARE2/reset-code")"
CODE3="$(jget "$R" "['data']['code']")"
check_eq "0" "$(jget "$R" "['code']")" "所有者重置提取码"
R="$(post_json_as "" "/api/public/share/$SHARE2/verify" "{\"code\":\"$CODE3\"}")"
check_eq "0" "$(jget "$R" "['code']")" "重置后立刻解锁并能下载"
R="$(api DELETE "/api/share/$SHARE2")"
check_eq "0" "$(jget "$R" "['code']")" "清理永久分享"

# ---------------------------------------------------------------- 3. 断点续传

section "3. 断点续传"
head -c $((SMALL_MB * 1024 * 1024)) /dev/urandom > "$WORK/resume.bin"
RS_HASH="$(sha256sum "$WORK/resume.bin" | cut -d' ' -f1)"
RS_SIZE="$(stat -c %s "$WORK/resume.bin")"
split -b "$CHUNK" -d -a 3 "$WORK/resume.bin" "$WORK/resume.part."

R="$(post_json /api/file/init "{\"fileName\":\"断点续传.bin\",\"fileSize\":$RS_SIZE,\"fileHash\":\"$RS_HASH\"}")"
RS_ID="$(jget "$R" "['data']['fileId']")"
RS_TOTAL="$(jget "$R" "['data']['chunkTotal']")"
HALF=$((RS_TOTAL / 2))
echo "  共 $RS_TOTAL 片，先只传前 $HALF 片，然后模拟『关掉浏览器再回来』"

for idx in $(seq 0 $((HALF - 1))); do
    part="$(printf '%s/resume.part.%03d' "$WORK" "$idx")"
    curl -s -X POST "$BASE/api/file/chunk" -H "Authorization: Bearer $TOKEN" \
        -F "fileId=$RS_ID" -F "index=$idx" -F "chunk=@$part" > /dev/null
done

# 模拟"关掉浏览器再回来"：重新 init 同一个文件，服务端应告诉客户端已经传了哪些片
R="$(post_json /api/file/init "{\"fileName\":\"断点续传.bin\",\"fileSize\":$RS_SIZE,\"fileHash\":\"$RS_HASH\"}")"
RS_ID2="$(jget "$R" "['data']['fileId']")"
check_eq "$RS_ID" "$RS_ID2" "重新 init 复用了同一个上传任务（没有新建）"
UPLOADED="$(jget "$R" "['data']['uploadedIndexes']")"
check_eq "$HALF" "$(jget "$R" "['data']['uploadedIndexes'].__len__()")" \
         "服务端回传了 $HALF 个已传分片序号"
echo "  ↳ uploadedIndexes = $UPLOADED"

# /status 也应以磁盘为准给出同样的已传分片
R="$(api GET "/api/file/$RS_ID/status")"
check_eq "$HALF" "$(jget "$R" "['data']['uploadedIndexes'].__len__()")" \
         "GET /status 以磁盘为准返回 $HALF 个已传分片"
check_eq "False" "$(jget "$R" "['data']['done']")" "未合并前 /status 的 done=false"

# 只补缺失的分片
for idx in $(seq "$HALF" $((RS_TOTAL - 1))); do
    part="$(printf '%s/resume.part.%03d' "$WORK" "$idx")"
    curl -s -X POST "$BASE/api/file/chunk" -H "Authorization: Bearer $TOKEN" \
        -F "fileId=$RS_ID" -F "index=$idx" -F "chunk=@$part" > /dev/null
done
R="$(api POST "/api/file/$RS_ID/complete")"
check_eq "0" "$(jget "$R" "['code']")" "补传剩余分片后合并成功"

dl -o "$WORK/resume-out.bin" "$BASE/api/file/$RS_ID/download"
check_eq "$RS_HASH" "$(sha256sum "$WORK/resume-out.bin" | cut -d' ' -f1)" "续传后的文件内容与原文件一致"

# ---------------------------------------------------------------- 4. 完整性校验

section "4. 完整性校验（内容对不上必须拒绝）"
# 造两个同样大小、内容不同的文件：声明 A 的摘要，却上传 B 的分片
head -c $((SMALL_MB * 1024 * 1024)) /dev/urandom > "$WORK/liar-a.bin"
head -c $((SMALL_MB * 1024 * 1024)) /dev/urandom > "$WORK/liar-b.bin"
LA_HASH="$(sha256sum "$WORK/liar-a.bin" | cut -d' ' -f1)"
LA_SIZE="$(stat -c %s "$WORK/liar-a.bin")"
split -b "$CHUNK" -d -a 3 "$WORK/liar-b.bin" "$WORK/liar.part."

R="$(post_json /api/file/init "{\"fileName\":\"内容被篡改.bin\",\"fileSize\":$LA_SIZE,\"fileHash\":\"$LA_HASH\"}")"
LIAR_ID="$(jget "$R" "['data']['fileId']")"
LIAR_TOTAL="$(jget "$R" "['data']['chunkTotal']")"
for idx in $(seq 0 $((LIAR_TOTAL - 1))); do
    part="$(printf '%s/liar.part.%03d' "$WORK" "$idx")"
    curl -s -X POST "$BASE/api/file/chunk" -H "Authorization: Bearer $TOKEN" \
        -F "fileId=$LIAR_ID" -F "index=$idx" -F "chunk=@$part" > /dev/null
done
R="$(api POST "/api/file/$LIAR_ID/complete")"
check_eq "30003" "$(jget "$R" "['code']")" "摘要对不上时合并被拒绝（code=30003）"
check_contains "$(jget "$R" "['msg']")" "校验失败" "错误信息说明了原因"

# 被拒绝的坏文件不该出现在列表里，也不该留下半成品（列表现在是分页结构）
R="$(api GET "/api/file/list?page=1&size=100")"
check_eq "0" "$(jget "$R" "['code']")" "GET /api/file/list 返回成功"
check_contains "$R" '"status":0' "坏文件仍停留在『上传中』，没有变成已完成"
check_contains "$R" '"total"' "列表返回总条数（分页元数据）"

# 分页参数必须真的生效：size=1 时只返回一条，且 total 是总数而不是当前页条数
R="$(api GET "/api/file/list?page=1&size=1")"
check_eq "1" "$(jget "$R" "['data']['items'].__len__()")" "分页 size=1 只返回 1 条"
check_eq "True" "$(jget "$R" "['data']['total'] > 1")" "分页 total 是总条数而不是当前页条数"

# 关键字搜索：按文件名命中
R="$(api GET "/api/file/list?keyword=%E5%8E%8B%E5%8A%9B%E6%B5%8B%E8%AF%95&size=100")"
check_eq "True" "$(jget "$R" "['data']['total'] > 0")" "按文件名关键字搜索能命中"

# 2 个汉字：走 ngram 全文索引（MATCH ... AGAINST）
R="$(api GET "/api/file/list?keyword=%E6%B5%8B%E8%AF%95&size=100")"
check_eq "True" "$(jget "$R" "['data']['total'] > 0")" "两个汉字的关键词走全文索引能命中"

# 1 个字：ngram 最小分词长度是 2，回退 LIKE
R="$(api GET "/api/file/list?keyword=%E6%B5%8B&size=100")"
check_eq "True" "$(jget "$R" "['data']['total'] > 0")" "单字关键词回退 LIKE 仍能命中"

# 完整 SHA-256：精确匹配，不走分词
R="$(api GET "/api/file/list?keyword=$BIG_HASH&size=100")"
check_eq "True" "$(jget "$R" "['data']['total'] > 0")" "完整 SHA-256 精确命中"

# 状态过滤：status=0 只返回上传中的记录
R="$(api GET "/api/file/list?status=0&size=100")"
check_eq "True" "$(jget "$R" "['data']['items'] and all(x['status'] == 0 for x in d['data']['items'])")" \
         "status=0 过滤后只剩上传中的记录"

# ---------------------------------------------------------------- 4.5 分片级校验

section "4.5 分片级校验（坏片在当前这一片就被拦下）"
head -c $((SMALL_MB * 1024 * 1024)) /dev/urandom > "$WORK/badchunk.bin"
BC_HASH="$(sha256sum "$WORK/badchunk.bin" | cut -d' ' -f1)"
BC_SIZE="$(stat -c %s "$WORK/badchunk.bin")"
split -b "$CHUNK" -d -a 3 "$WORK/badchunk.bin" "$WORK/badchunk.part."

R="$(post_json /api/file/init "{\"fileName\":\"坏分片测试.bin\",\"fileSize\":$BC_SIZE,\"fileHash\":\"$BC_HASH\"}")"
BC_ID="$(jget "$R" "['data']['fileId']")"
BC_TOTAL="$(jget "$R" "['data']['chunkTotal']")"

# 故意把 index=0 的摘要写成另一个文件的摘要：服务端落盘时复算，应当拒绝这一片
WRONG_HASH="$(sha256sum "$WORK/liar-b.bin" | cut -d' ' -f1)"
R="$(curl -s -X POST "$BASE/api/file/chunk" -H "Authorization: Bearer $TOKEN" \
        -F "fileId=$BC_ID" -F "index=0" \
        -F "chunkHash=$WRONG_HASH" -F "chunk=@$WORK/badchunk.part.000")"
check_eq "30004" "$(jget "$R" "['code']")" "分片摘要不符时返回 30004"
check_contains "$(jget "$R" "['msg']")" "请重传该分片" "错误信息提示只重传该分片"

# 被拒绝的片不能落盘，也不能留下 .incoming 临时文件
R="$(api GET "/api/file/$BC_ID/status")"
check_eq "0" "$(jget "$R" "['data']['uploadedIndexes'].__len__()")" \
         "坏分片没有计入已传列表"
LEFT_IN="$(find "$STORAGE_ROOT/chunks/$BC_ID" -name '*.incoming' 2>/dev/null | wc -l | tr -d ' ')"
check_eq "0" "$LEFT_IN" "坏分片没有留下 .incoming 临时文件"

# 用正确的摘要重传，再把其余分片传完，最终仍应合并成功
for idx in $(seq 0 $((BC_TOTAL - 1))); do
    part="$(printf '%s/badchunk.part.%03d' "$WORK" "$idx")"
    part_hash="$(sha256sum "$part" | cut -d' ' -f1)"
    curl -s -X POST "$BASE/api/file/chunk" -H "Authorization: Bearer $TOKEN" \
        -F "fileId=$BC_ID" -F "index=$idx" \
        -F "chunkHash=$part_hash" -F "chunk=@$part" > /dev/null
done
R="$(api POST "/api/file/$BC_ID/complete")"
check_eq "0" "$(jget "$R" "['code']")" "坏片重传后合并成功"

# ---------------------------------------------------------------- 4.6 坏片自愈

section "4.6 合并时发现磁盘分片损坏，只补坏片"
head -c $((SMALL_MB * 1024 * 1024)) /dev/urandom > "$WORK/heal.bin"
HEAL_HASH="$(sha256sum "$WORK/heal.bin" | cut -d' ' -f1)"
HEAL_SIZE="$(stat -c %s "$WORK/heal.bin")"
split -b "$CHUNK" -d -a 3 "$WORK/heal.bin" "$WORK/heal.part."

R="$(post_json /api/file/init "{\"fileName\":\"坏片自愈测试.bin\",\"fileSize\":$HEAL_SIZE,\"fileHash\":\"$HEAL_HASH\"}")"
HEAL_ID="$(jget "$R" "['data']['fileId']")"
HEAL_TOTAL="$(jget "$R" "['data']['chunkTotal']")"
for idx in $(seq 0 $((HEAL_TOTAL - 1))); do
    part="$(printf '%s/heal.part.%03d' "$WORK" "$idx")"
    part_hash="$(sha256sum "$part" | cut -d' ' -f1)"
    curl -s -X POST "$BASE/api/file/chunk" -H "Authorization: Bearer $TOKEN" \
        -F "fileId=$HEAL_ID" -F "index=$idx" \
        -F "chunkHash=$part_hash" -F "chunk=@$part" > /dev/null
done

# 模拟"落盘之后磁盘被悄悄改坏"：大小不变，只翻一个字节
HEAL_CHUNK="$STORAGE_ROOT/chunks/$HEAL_ID/1.part"
if [ -f "$HEAL_CHUNK" ]; then
    # 用 Python 翻一个字节，比 dd 在 Git Bash 下更可移植
    python - "$HEAL_CHUNK" <<'PY'
import sys
p = sys.argv[1]
with open(p, 'r+b') as f:
    f.seek(128)
    b = f.read(1)
    f.seek(128)
    f.write(bytes([b[0] ^ 0xFF]))
PY
    R="$(api POST "/api/file/$HEAL_ID/complete")"
    check_eq "30005" "$(jget "$R" "['code']")" "合并时发现坏片并返回 30005"
    check_contains "$(jget "$R" "['msg']")" "损坏分片" "错误信息说明是损坏分片"

    R="$(api GET "/api/file/$HEAL_ID/status")"
    check_eq "False" "$(jget "$R" "['data']['uploadedIndexes'].__contains__(1)")" \
             "坏片已从已传列表中剔除"
    check_eq "$((HEAL_TOTAL - 1))" "$(jget "$R" "['data']['uploadedIndexes'].__len__()")" \
             "其余分片仍然保留，只需补传坏的那一片"

    # 只补传 index=1，再合并
    curl -s -X POST "$BASE/api/file/chunk" -H "Authorization: Bearer $TOKEN" \
        -F "fileId=$HEAL_ID" -F "index=1" \
        -F "chunkHash=$(sha256sum "$WORK/heal.part.001" | cut -d' ' -f1)" \
        -F "chunk=@$WORK/heal.part.001" > /dev/null
    R="$(api POST "/api/file/$HEAL_ID/complete")"
    check_eq "0" "$(jget "$R" "['code']")" "只补坏片后合并成功"
    dl -o "$WORK/heal-out.bin" "$BASE/api/file/$HEAL_ID/download"
    check_eq "$HEAL_HASH" "$(sha256sum "$WORK/heal-out.bin" | cut -d' ' -f1)" \
             "自愈后的文件内容与原文件一致"
else
    bad "找不到分片文件 $HEAL_CHUNK（检查 STORAGE_ROOT / FILE_HUB_ROOT）"
fi

# ---------------------------------------------------------------- 4.8 管理员完整性

if [ -n "$TOKEN_ADMIN" ]; then
    section "4.8 管理员完整性扫描与修复"
    CODE="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN_ADMIN" \
        "$BASE/actuator/prometheus")"
    check_eq "200" "$CODE" "管理员可以读取 Prometheus 指标"
    if ! curl -s "$BASE/api/file/config" | grep -q '"desktop":true'; then
        R_DOCS="$(api_as "$TOKEN_ADMIN" GET "/v3/api-docs")"
        check_contains "$R_DOCS" '"title":"file-hub API"' "管理员可以读取 OpenAPI 文档"
    fi

    # HEAL_ID 已经合并完成；手工删掉物理文件，模拟磁盘丢失/外部误删
    HEAL_FILE="$(find "$STORAGE_ROOT/files" -type f -name "$HEAL_ID" 2>/dev/null | head -n1)"
    if [ -n "$HEAL_FILE" ]; then
        rm -f "$HEAL_FILE"

        R="$(api_as "$TOKEN_ADMIN" GET "/api/admin/integrity/scan?deep=false&limit=1000")"
        check_eq "0" "$(jget "$R" "['code']")" "管理员可以执行完整性扫描"
        check_contains "$R" "$HEAL_ID" "扫描发现物理文件丢失的记录"
        check_contains "$R" "物理文件不存在" "扫描给出了具体原因"

        CODE="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
            "$BASE/api/admin/integrity/scan")"
        check_eq "403" "$CODE" "普通用户访问管理员接口返回 403"

        R="$(post_json_as "$TOKEN_ADMIN" /api/admin/integrity/repair \
            "{\"fileIds\":[\"$HEAL_ID\"]}")"
        check_eq "1" "$(jget "$R" "['data']['repaired']")" "修复删除已确认损坏的记录"

        R="$(api GET "/api/file/list?page=1&size=100&keyword=$HEAL_ID")"
        check_eq "0" "$(jget "$R" "['data']['total']")" "修复后记录从列表中消失"
    else
        bad "找不到 $HEAL_ID 的物理文件（检查 STORAGE_ROOT / FILE_HUB_ROOT）"
    fi
else
    echo "  ↳ 未设置 FILE_HUB_ADMIN_PASSWORD，跳过管理员完整性用例"
fi

# ---------------------------------------------------------------- 4.9 v8 企业与网盘能力端到端

section "4.9 在线预览 / 目录树 / Zip 打包 / 回收站 / 免密限次分享 / 吊销 / WebDAV / 审计"

printf 'hello-v8-%s\n' "$RUN_ID" > "$WORK/v8-note.txt"
V8_HASH="$(sha256sum "$WORK/v8-note.txt" | cut -d' ' -f1)"
V8_SIZE="$(stat -c %s "$WORK/v8-note.txt")"
R="$(post_json /api/file/init "{\"fileName\":\"v8-note-$RUN_ID.txt\",\"fileSize\":$V8_SIZE,\"fileHash\":\"$V8_HASH\"}")"
V8_ID="$(jget "$R" "['data']['fileId']")"
V8_INSTANT="$(jget "$R" "['data']['instant']")"
if [ "$V8_INSTANT" != "True" ]; then
    curl -s -X POST "$BASE/api/file/chunk" -H "Authorization: Bearer $TOKEN" \
        -F "fileId=$V8_ID" -F "index=0" -F "chunkHash=$V8_HASH" \
        -F "chunk=@$WORK/v8-note.txt" > /dev/null
    api POST "/api/file/$V8_ID/complete" > /dev/null
fi

# 在线预览 (?inline=true)
curl -s -D "$WORK/inline-hdr.txt" -o "$WORK/inline-body.txt" -H "Authorization: Bearer $TOKEN" \
    "$BASE/api/file/$V8_ID/download?inline=true"
INLINE_HDR="$(tr -d '\r' < "$WORK/inline-hdr.txt")"
check_contains "$INLINE_HDR" "inline" "预览响应 Content-Disposition 为 inline"
check_eq "$V8_HASH" "$(sha256sum "$WORK/inline-body.txt" | cut -d' ' -f1)" "预览内容与原文件一致"

# 目录创建、移动与树浏览
R="$(post_json /api/file/folder "{\"path\":\"v8-docs-$RUN_ID\"}")"
check_eq "0" "$(jget "$R" "['code']")" "显式创建文件夹成功"
R="$(post_json "/api/file/$V8_ID/move" "{\"targetDir\":\"v8-docs-$RUN_ID\",\"newName\":\"renamed-$RUN_ID.txt\"}")"
check_eq "0" "$(jget "$R" "['code']")" "文件移动与重命名成功"
R="$(api GET "/api/file/tree?dir=v8-docs-$RUN_ID")"
check_contains "$R" "renamed-$RUN_ID.txt" "目录树列出移动后的文件"

# 批量打包 Zip
R="$(post_json /api/file/zip-ticket "{\"fileIds\":[\"$V8_ID\"]}")"
ZIP_URL="$(jget "$R" "['data']['downloadUrl']")"
curl -s -o "$WORK/bundle.zip" "$BASE$ZIP_URL"
ZIP_CHECK="$(python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); print(','.join(z.namelist()))" "$WORK/bundle.zip" 2>/dev/null)"
check_contains "$ZIP_CHECK" "renamed-$RUN_ID.txt" "Zip 打包流中包含目标文件"

# 回收站移入 -> 隐藏 -> 恢复
R="$(api POST "/api/file/$V8_ID/trash")"
check_eq "0" "$(jget "$R" "['code']")" "文件移入回收站成功"
R="$(api GET "/api/file/list?keyword=renamed-$RUN_ID.txt")"
check_eq "0" "$(jget "$R" "['data']['total']")" "回收站文件不再出现在主列表"
R="$(api GET "/api/file/trash")"
check_contains "$R" "$V8_ID" "回收站列表包含已删文件"
R="$(api POST "/api/file/$V8_ID/restore")"
check_eq "0" "$(jget "$R" "['code']")" "从回收站恢复文件成功"

# 免密限次分享 + 转存 + 取消分享立即吊销已发票据
R="$(post_json /api/share "{\"fileId\":\"$V8_ID\",\"expiresInDays\":7,\"requireCode\":false,\"maxDownloads\":1}")"
V8_SHARE_TOKEN="$(jget "$R" "['data']['token']")"
check_eq "False" "$(jget "$R" "['data']['requireCode']")" "支持免提取码公开分享"
R="$(post_json_as "" "/api/public/share/$V8_SHARE_TOKEN/verify" "{}")"
V8_DL_URL="$(jget "$R" "['data']['downloadUrl']")"
check_contains "$V8_DL_URL" "ticket=" "免提取码首次提取返回下载票据"
R="$(post_json_as "" "/api/public/share/$V8_SHARE_TOKEN/verify" "{}")"
check_eq "42009" "$(jget "$R" "['code']")" "超出 maxDownloads 后第 2 次提取返回 42009"

V8_TICKET="${V8_DL_URL#*ticket=}"
R="$(post_json_as "$TOKEN_B" "/api/share/$V8_SHARE_TOKEN/save" "{\"ticket\":\"$V8_TICKET\"}")"
V8_SAVED_ID="$(jget "$R" "['data']['fileId']")"
check_eq "0" "$(jget "$R" "['code']")" "用户 B 凭分享转存到自己网盘成功"

CODE_BEFORE_CANCEL="$(curl -s -o /dev/null -w '%{http_code}' "$BASE$V8_DL_URL")"
check_eq "200" "$CODE_BEFORE_CANCEL" "取消前分享下载票据有效"
api DELETE "/api/share/$V8_SHARE_TOKEN" > /dev/null
CODE_AFTER_CANCEL="$(curl -s -o /dev/null -w '%{http_code}' "$BASE$V8_DL_URL")"
check_eq "401" "$CODE_AFTER_CANCEL" "取消分享后同一票据立即被吊销（返回 401）"

# WebDAV（OPTIONS / MKCOL / PUT / PROPFIND / GET / DELETE）
CODE_DAV_OPT="$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS "$BASE/dav/")"
check_eq "200" "$CODE_DAV_OPT" "WebDAV OPTIONS 返回 200"
CODE_DAV_MKCOL="$(curl -s -o /dev/null -w '%{http_code}' -u "$USER_A:$PASSWORD" -X MKCOL "$BASE/dav/dav-$RUN_ID")"
check_eq "201" "$CODE_DAV_MKCOL" "WebDAV MKCOL 创建目录成功"
CODE_DAV_PUT="$(curl -s -o /dev/null -w '%{http_code}' -u "$USER_A:$PASSWORD" -T "$WORK/v8-note.txt" "$BASE/dav/dav-$RUN_ID/dav.txt")"
check_eq "201" "$CODE_DAV_PUT" "WebDAV PUT 上传文件成功"
DAV_PROP="$(curl -s -u "$USER_A:$PASSWORD" -X PROPFIND -H "Depth: 1" "$BASE/dav/dav-$RUN_ID")"
check_contains "$DAV_PROP" "dav.txt" "WebDAV PROPFIND 列出刚上传文件"
curl -s -u "$USER_A:$PASSWORD" -o "$WORK/dav-out.txt" "$BASE/dav/dav-$RUN_ID/dav.txt"
check_eq "$V8_HASH" "$(sha256sum "$WORK/dav-out.txt" | cut -d' ' -f1)" "WebDAV GET 下载内容与原文件一致"
CODE_DAV_DEL="$(curl -s -o /dev/null -w '%{http_code}' -u "$USER_A:$PASSWORD" -X DELETE "$BASE/dav/dav-$RUN_ID")"
check_eq "204" "$CODE_DAV_DEL" "WebDAV DELETE 目录移入回收站成功"

# 审计日志校验 + 清理本节临时文件
R="$(api GET "/api/audit/logs?page=1&size=20")"
check_contains "$R" "WEBDAV_PUT" "审计日志记录 WebDAV 写入操作"
api DELETE "/api/file/$V8_ID" > /dev/null
api_as "$TOKEN_B" DELETE "/api/file/$V8_SAVED_ID" > /dev/null
api DELETE "/api/file/trash" > /dev/null


# ---------------------------------------------------------------- 5. 引用计数删除

section "5. 删除与引用计数"
# 秒传产生的两条记录共享同一个物理文件，删掉一条时物理文件必须留着
R="$(api DELETE "/api/file/$SECOND_ID")"
check_eq "0" "$(jget "$R" "['code']")" "删除秒传产生的第二条记录"
dl -o "$WORK/still.bin" "$BASE/api/file/$FILE_ID/download"
check_eq "$BIG_HASH" "$(sha256sum "$WORK/still.bin" | cut -d' ' -f1)" \
         "删掉一条后，原文件仍可下载（物理文件没被误删）"

# 删掉最后一条引用，物理文件才真的消失
api DELETE "/api/file/$FILE_ID" > /dev/null
R="$(api GET "/api/file/$FILE_ID/download")"
check_contains "$R" '"code":10005' "最后一条引用删除后，文件不存在了"

LEFT="$(find "$STORAGE_ROOT/files" -type f 2>/dev/null | wc -l)"
echo "  ↳ 存储目录里还剩 $LEFT 个成品文件"

# 完整性校验用例故意留下一个「上传中」任务，这里删掉，
# 保证脚本可以反复执行而不在库里攒测试垃圾（删除上传中任务会连分片一起清）。
api DELETE "/api/file/$LIAR_ID" > /dev/null

# ---------------------------------------------------------------- 汇总

section "结果"
printf '  通过 %d 项，失败 %d 项\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] && { printf '  \033[32m全部通过\033[0m\n'; exit 0; }
printf '  \033[31m有失败项\033[0m\n'
exit 1
