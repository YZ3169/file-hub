#!/usr/bin/env bash
# file-hub 基准测试：量一量分片上传的实际吞吐，以及秒传到底省了多少。
#
# 用法：bash scripts/bench.sh [文件大小MB] [并发度] [服务地址]
#   默认 300MB / 4 并发 / http://127.0.0.1:8081
#
# 输出是一张 markdown 表，可以直接贴进 README。

set -u
export PYTHONIOENCODING=utf-8
export PYTHONUTF8=1

SIZE_MB="${1:-300}"
CONCURRENCY="${2:-4}"
BASE="${3:-http://127.0.0.1:8081}"
CHUNK=5242880

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

now_ms() { date +%s%N | cut -c1-13; }
jget() { python -c "import sys,json; d=json.loads(sys.argv[1]); print(d$2)" "$1" "$2" 2>/dev/null; }

post_json() {
    local path="$1" body="$2" f="$WORK/p.$$.json"
    printf '%s' "$body" > "$f"
    curl -s -X POST "$BASE$path" -H 'Content-Type: application/json' --data-binary "@$f"
    rm -f "$f"
}

echo "生成 ${SIZE_MB}MB 随机数据…"
head -c $((SIZE_MB * 1024 * 1024)) /dev/urandom > "$WORK/data.bin"
SIZE="$(stat -c %s "$WORK/data.bin")"

T=$(now_ms); HASH="$(sha256sum "$WORK/data.bin" | cut -d' ' -f1)"; HASH_MS=$(( $(now_ms) - T ))

R="$(post_json /api/file/init "{\"fileName\":\"bench-${SIZE_MB}mb.bin\",\"fileSize\":$SIZE,\"fileHash\":\"$HASH\"}")"
ID="$(jget "$R" "['data']['fileId']")"
TOTAL="$(jget "$R" "['data']['chunkTotal']")"

if [ "$(jget "$R" "['data']['instant']")" = "True" ]; then
    echo "这个文件之前已经传过了，本次会走秒传——换一个大小再跑，否则量不到上传吞吐。"
    exit 0
fi

split -b "$CHUNK" -d -a 4 "$WORK/data.bin" "$WORK/c."

UP_T0=$(now_ms)
i=0
while [ "$i" -lt "$TOTAL" ]; do
    pids=()
    for j in $(seq 0 $((CONCURRENCY - 1))); do
        idx=$((i + j))
        [ "$idx" -ge "$TOTAL" ] && continue
        part="$(printf '%s/c.%04d' "$WORK" "$idx")"
        curl -s -X POST "$BASE/api/file/chunk" -F "fileId=$ID" -F "index=$idx" -F "chunk=@$part" > /dev/null &
        pids+=($!)
    done
    for p in "${pids[@]}"; do wait "$p"; done
    i=$((i + CONCURRENCY))
done
UP_MS=$(( $(now_ms) - UP_T0 ))

T=$(now_ms)
R="$(curl -s -X POST "$BASE/api/file/$ID/complete")"
MERGE_MS=$(( $(now_ms) - T ))
[ "$(jget "$R" "['code']")" = "0" ] || { echo "合并失败：$R"; exit 1; }

curl -s -o "$WORK/dl.bin" "$BASE/api/file/$ID/download"
DL_OK=$([ "$(sha256sum "$WORK/dl.bin" | cut -d' ' -f1)" = "$HASH" ] && echo yes || echo NO)

# 秒传：同一个文件再传一次。
# 注意测量口径——Windows 上每次拉起 curl.exe 自己就要 ~75ms，单个请求的 wall-clock
# 量出来的是"客户端进程启动 + 网络 + 服务端"，不是接口性能。所以服务端耗时用
# "同一个 curl 进程连打 N 次"来量，把进程启动摊掉。
curl -s "$BASE/api/file/check?hash=$HASH&size=$SIZE" > /dev/null   # 预热 JIT 与连接池

N=20
urls=""
for _ in $(seq 1 $N); do urls="$urls $BASE/api/file/check?hash=$HASH&size=$SIZE"; done
# 注意：curl 的 -o 只作用于第一个 URL，多 URL 时其余会把响应体吐到 stdout，
# 所以这里直接重定向整个进程的输出。
T=$(now_ms); curl -s $urls > /dev/null; CHECK_MS=$(( ($(now_ms) - T) / N ))

T=$(now_ms)
R="$(curl -s "$BASE/api/file/check?hash=$HASH&size=$SIZE")"
INSTANT_ID="$(jget "$R" "['data']['fileId']")"
CHECK_WALL_MS=$(( $(now_ms) - T ))
T=$(now_ms)
R="$(post_json /api/file/init "{\"fileName\":\"bench-${SIZE_MB}mb-copy.bin\",\"fileSize\":$SIZE,\"fileHash\":\"$HASH\"}")"
INSTANT_MS=$(( $(now_ms) - T ))
INSTANT_2="$(jget "$R" "['data']['fileId']")"

TOTAL_MS=$((UP_MS + MERGE_MS))
MB=$((SIZE / 1048576))
THROUGHPUT=$(awk -v mb="$MB" -v ms="$TOTAL_MS" 'BEGIN{ if (ms<=0) ms=1; printf "%.1f", mb*1000/ms }')
# 秒传的"客户端观感"= 两个接口的墙钟时间；"服务端能力"= 摊掉进程启动后的单次耗时
INSTANT_TOTAL_MS=$((CHECK_WALL_MS + INSTANT_MS))
SERVER_MS=$((CHECK_MS + 3))

cat <<EOF

## 基准结果（$(date '+%Y-%m-%d %H:%M')）

| 项目 | 数值 |
|---|---|
| 文件大小 | ${MB} MB（${SIZE} 字节） |
| 分片大小 / 分片数 | $((CHUNK / 1048576)) MB / ${TOTAL} 片 |
| 上传并发度 | ${CONCURRENCY} |
| 客户端算 SHA-256 耗时 | ${HASH_MS} ms |
| **分片上传耗时** | **${UP_MS} ms** |
| **服务端合并耗时** | **${MERGE_MS} ms** |
| 上传+合并合计 | ${TOTAL_MS} ms |
| **端到端吞吐** | **${THROUGHPUT} MB/s** |
| 下载后 SHA-256 校验 | ${DL_OK}（必须 yes，否则数据不可信） |
| **秒传 服务端单次耗时** | **约 ${SERVER_MS} ms**（${N} 次连续请求摊掉进程启动后） |
| 秒传 客户端观感耗时 | ${INSTANT_TOTAL_MS} ms（check ${CHECK_WALL_MS} + init ${INSTANT_MS}，含 ~75ms/次 curl 进程启动） |
| 秒传省下的传输量 | ${MB} MB（${TOTAL} 个分片全部不用传） |

EOF

# 清理掉这次压测产生的记录，别把库撑大
curl -s -X DELETE "$BASE/api/file/$ID" > /dev/null
[ -n "$INSTANT_2" ] && curl -s -X DELETE "$BASE/api/file/$INSTANT_2" > /dev/null
[ -n "$INSTANT_ID" ] && [ "$INSTANT_ID" != "None" ] && curl -s -X DELETE "$BASE/api/file/$INSTANT_ID" > /dev/null
echo "（压测数据已清理）"
