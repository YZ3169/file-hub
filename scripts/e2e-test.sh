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

WORK="$(mktemp -d)"
PASS=0
FAIL=0

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

section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

api() {  # api METHOD PATH [curl args...]
    local method="$1" path="$2"; shift 2
    curl -s -X "$method" "$BASE$path" "$@"
}

# 发 JSON 一律走文件，不用 `-d '{...}'` 内联。
# 原因：Windows 上 curl.exe 是原生程序，命令行参数会被按系统 ANSI 代码页（本机是 GBK）转换，
# 而 JSON 必须是 UTF-8 —— 内联中文文件名会让服务端报 "Invalid UTF-8 start byte 0xb2"。
# 写进文件再 --data-binary @ 读，是字节透传，不受 argv 编码影响。
post_json() {  # post_json PATH JSON
    local path="$1" body="$2"
    local f="$WORK/payload.$$.$RANDOM.json"
    printf '%s' "$body" > "$f"
    local out
    out="$(curl -s -X POST "$BASE$path" -H 'Content-Type: application/json' --data-binary "@$f")"
    rm -f "$f"
    printf '%s' "$out"
}

# ---------------------------------------------------------------- 前置检查

section "0. 服务可达性"
CONFIG="$(api GET /api/file/config)"
check_contains "$CONFIG" '"code":0' "GET /api/file/config 返回成功"
CFG_CHUNK="$(jget "$CONFIG" "['data']['chunkSize']")"
check_eq "$CHUNK" "$CFG_CHUNK" "服务端分片大小与脚本一致"
[ "$FAIL" -gt 0 ] && { echo "服务没起来，后面的用例没意义，先退出"; exit 1; }

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
        (
            r="$(curl -s -X POST "$BASE/api/file/chunk" \
                    -F "fileId=$FILE_ID" -F "index=$idx" -F "chunk=@$part")"
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
R="$(curl -s -X POST "$BASE/api/file/chunk" -F "fileId=$FILE_ID" -F "index=0" -F "chunk=@$WORK/big.part.000")"
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
curl -s -o "$WORK/out.bin" "$BASE/api/file/$FILE_ID/download"
OUT_HASH="$(sha256sum "$WORK/out.bin" | cut -d' ' -f1)"
check_eq "$BIG_HASH" "$OUT_HASH" "下载回来的文件与原文件 SHA-256 一致"

# ---------------------------------------------------------------- 2. 秒传

section "2. 秒传（内容寻址去重）"
R="$(api GET "/api/file/check?hash=$BIG_HASH&size=$BIG_SIZE")"
check_eq "True" "$(jget "$R" "['data']['exists']")" "预检命中已有文件"

R="$(post_json /api/file/init "{\"fileName\":\"换个名字-同一个文件.bin\",\"fileSize\":$BIG_SIZE,\"fileHash\":\"$BIG_HASH\"}")"
check_eq "True" "$(jget "$R" "['data']['instant']")" "再次 init 直接返回 instant=true"
SECOND_ID="$(jget "$R" "['data']['fileId']")"
echo "  ↳ 秒传得到新的 fileId=$SECOND_ID，一个字节都没传"

# 命中文件应当能正常下载（说明它确实指向了已有的物理文件）
curl -s -o "$WORK/instant.bin" "$BASE/api/file/$SECOND_ID/download"
check_eq "$BIG_HASH" "$(sha256sum "$WORK/instant.bin" | cut -d' ' -f1)" "秒传文件能下载且内容正确"

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
    curl -s -X POST "$BASE/api/file/chunk" -F "fileId=$RS_ID" -F "index=$idx" -F "chunk=@$part" > /dev/null
done

# 模拟"关掉浏览器再回来"：重新 init 同一个文件，服务端应告诉客户端已经传了哪些片
R="$(post_json /api/file/init "{\"fileName\":\"断点续传.bin\",\"fileSize\":$RS_SIZE,\"fileHash\":\"$RS_HASH\"}")"
RS_ID2="$(jget "$R" "['data']['fileId']")"
check_eq "$RS_ID" "$RS_ID2" "重新 init 复用了同一个上传任务（没有新建）"
UPLOADED="$(jget "$R" "['data']['uploadedIndexes']")"
check_eq "$HALF" "$(jget "$R" "['data']['uploadedIndexes'].__len__()")" \
         "服务端回传了 $HALF 个已传分片序号"
echo "  ↳ uploadedIndexes = $UPLOADED"

# 只补缺失的分片
for idx in $(seq "$HALF" $((RS_TOTAL - 1))); do
    part="$(printf '%s/resume.part.%03d' "$WORK" "$idx")"
    curl -s -X POST "$BASE/api/file/chunk" -F "fileId=$RS_ID" -F "index=$idx" -F "chunk=@$part" > /dev/null
done
R="$(api POST "/api/file/$RS_ID/complete")"
check_eq "0" "$(jget "$R" "['code']")" "补传剩余分片后合并成功"

curl -s -o "$WORK/resume-out.bin" "$BASE/api/file/$RS_ID/download"
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
    curl -s -X POST "$BASE/api/file/chunk" -F "fileId=$LIAR_ID" -F "index=$idx" -F "chunk=@$part" > /dev/null
done
R="$(api POST "/api/file/$LIAR_ID/complete")"
check_eq "30003" "$(jget "$R" "['code']")" "摘要对不上时合并被拒绝（code=30003）"
check_contains "$(jget "$R" "['msg']")" "校验失败" "错误信息说明了原因"

# 被拒绝的坏文件不该出现在列表里，也不该留下半成品
R="$(api GET /api/file/list)"
check_contains "$R" '"status":0' "坏文件仍停留在『上传中』，没有变成已完成"

# ---------------------------------------------------------------- 5. 引用计数删除

section "5. 删除与引用计数"
# 秒传产生的两条记录共享同一个物理文件，删掉一条时物理文件必须留着
R="$(api DELETE "/api/file/$SECOND_ID")"
check_eq "0" "$(jget "$R" "['code']")" "删除秒传产生的第二条记录"
curl -s -o "$WORK/still.bin" "$BASE/api/file/$FILE_ID/download"
check_eq "$BIG_HASH" "$(sha256sum "$WORK/still.bin" | cut -d' ' -f1)" \
         "删掉一条后，原文件仍可下载（物理文件没被误删）"

# 删掉最后一条引用，物理文件才真的消失
api DELETE "/api/file/$FILE_ID" > /dev/null
R="$(api GET "/api/file/$FILE_ID/download")"
check_contains "$R" '"code":10005' "最后一条引用删除后，文件不存在了"

STORAGE_ROOT="D:/Projects/file-hub-data"
LEFT="$(find "$STORAGE_ROOT/files" -type f 2>/dev/null | wc -l)"
echo "  ↳ 存储目录里还剩 $LEFT 个成品文件"

# ---------------------------------------------------------------- 汇总

section "结果"
printf '  通过 %d 项，失败 %d 项\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] && { printf '  \033[32m全部通过\033[0m\n'; exit 0; }
printf '  \033[31m有失败项\033[0m\n'
exit 1
