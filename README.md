# file-hub

一个自己实现存储层的文件服务：**分片上传 / 秒传 / 断点续传 / 分片级校验与自动补传**，
外加一个 Vue 3 + Vite 的可视化传输工作台。

和"调一个 OSS SDK 把文件传上去"不同，这个项目把对象存储那一层的语义自己写了一遍——
内容寻址、分片状态、合并的原子性、并发去重——所以它替换掉本地磁盘实现就能接 OSS/MinIO，
而不是把业务代码绑死在某个云厂商的 SDK 上。

前端构建产物直接打进 `src/main/resources/static/`，**一个 jar + 一个静态工作台**，
不需要额外起 Node 服务；原来的单页演示保留在 `/classic.html`。

> **关于 AI 辅助研发**
> 本项目的代码由 Claude Code 按我给出的设计约束生成，我在其基础上逐文件阅读、调试、
> 修改并跑通全部验证。**这不是"我一行行手敲的"项目**，如果你在面试里问起，我会如实说明，
> 并且能解释其中任何一段代码为什么这么写。README 下面「我需要能回答的追问」一节
> 列的就是我为这个项目准备的问题清单。

---

## 它解决什么问题

浏览器把 2GB 的文件一次性 POST 上去，会同时踩四个坑：

| 坑 | 后果 | 本项目的做法 |
|---|---|---|
| 请求体过大 | 网关/Tomcat 直接 413，或者传到 90% 断线 | 前端 `File.slice()` 切成 5MB 的分片，逐片上传 |
| 断线要从头再来 | 白传 2GB | 分片状态落库，重连后只补缺失的片（断点续传） |
| 某一片传坏了 | 只能整文件重传 | 每片带 SHA-256，服务端落盘时复算；坏片返回 30004，客户端只重传这一片 |
| 合并时才发现内容不对 | 静默产生损坏文件，或整文件重来 | 合并时逐片复算 + 整文件复算；只删坏片并返回 30005，客户端自愈补传 |
| 同一个文件反复占空间 | 100 个人传同一份课件 = 100 份 | 按内容寻址，秒传 + 合并后去重，磁盘上只有一份 |

---

## 架构

```
  Vue 3 工作台（web/ → static/）            /classic.html 是原始单页演示
     │  ① 增量算整文件 SHA-256（WebCrypto 不行，见下），结果缓存进 localStorage
     │  ② POST /api/file/init       → fileId + 已传分片序号 + 是否秒传命中
     │  ③ POST /api/file/chunk      ×N，并发 4；每片附带 WebCrypto 算的 SHA-256
     │  ④ 失败分片指数退避重试；暂停/刷新后从 /status 或 init 重新对齐
     │  ⑤ POST /api/file/{id}/complete  逐片复算 + 整文件复算 → 流式合并 → 原子落盘
     │  ⑥ 30005 坏片 → 只补坏片（最多 2 轮自愈）；下载支持 HTTP Range
     ▼
  ┌──────────────────────────────────────────────┐
  │  FileController      Result<T> 统一返回       │
  ├──────────────────────────────────────────────┤
  │  FileStorageService  秒传 / 幂等 / 完整性校验 │
  ├──────────────────────┬───────────────────────┤
  │  StorageBackend      │  MySQL                │
  │   └ LocalDiskStorage │   file_info           │
  │     files/ chunks/   │   file_chunk          │
  └──────────────────────┴───────────────────────┘
```

`StorageBackend` 接口刻意**不出现任何 `File` / `Path` 类型**，只描述"按 key 存取一段不透明字节"。
想换成 OSS，只需要再写一个实现，业务层一行不用改。

---

## 关键设计取舍（面试真正会问的部分）

### 1. 为什么分片总数由服务端算

`init` 请求里**没有** `chunkTotal` 字段。早期版本让前端传，结果前端向上取整算错一次，
合并出来的文件就少一截——而且直到最后的 hash 校验才暴露。
**能从 `fileSize` 和 `chunkSize` 推导出来的东西，就不要让调用方传。**

### 2. 为什么判"齐没齐"以磁盘为准，而不是查数据库

`file_chunk` 表记着哪些片传上来了，但合并前判断缺片时，代码用的是
`LocalDiskStorage.listChunksOnDisk()` 扫磁盘目录。

理由：数据库和磁盘可能不一致（磁盘被清理、备份恢复漏了目录）。
如果信数据库，就会用"数据库说有的片"去拼文件，拼出来是坏的；
扫磁盘则会如实报出缺哪几片，让前端补传。**数据库是索引，磁盘才是事实。**

那 `file_chunk` 表还有什么用？三个用途：
① **并发幂等**——同一分片被并发上传时靠唯一索引 `uk_file_chunk` 拦下第二次；
② **保存每片上传时服务端复算的 SHA-256**，合并时逐片比对，发现"落盘后被改坏"的片；
③ 审计与统计。它不是"文件齐了没"的判据。

### 3. 合并为什么必须流式

```java
for (int i = 0; i < chunkTotal; i++) {
    try (InputStream in = Files.newInputStream(chunkFile(fileId, i))) {
        in.transferTo(digestOut);   // 8KB 缓冲区，内存恒定
    }
}
```

一个 2GB 的文件如果先读成一个 `byte[]` 再写，至少要 2GB 堆内存，直接 OOM。
流式合并的内存占用与文件大小**无关**。

### 4. 边写边算摘要

合并用的是 `DigestOutputStream` 包住文件输出流：写盘的同一遍就把 SHA-256 算完了，
不用合并完再读一遍文件重算。1GB 的文件因此少一次完整的磁盘读。

### 5. 秒传的去重不变量

**同一个 `(hash, size)` 在磁盘上只有一份成品。** 这个不变量在两个时机被应用：

- **上传前**（秒传）：`check` 命中就直接建一条已完成记录，指向已有的 `storage_path`；
- **上传后**（并发去重）：两个请求同时传同一个新文件，都错过了秒传预检，
  后合并完的那个发现库里已经有同样内容的成品，就把自己刚拼好的临时文件删掉、复用对方的。

代价是**多条逻辑记录共享一份物理文件**，所以删除时不能见一条删一条：

```java
int stillReferenced = fileInfoMapper.countByStoragePath(info.getStoragePath());
if (stillReferenced == 0) { storage.delete(path); }   // 只有最后一个引用才真删
```

### 6. 用 SHA-256 而不是 MD5

MD5 已有实用化的碰撞攻击。用 MD5 当"内容相同"的判据，意味着攻击者可以构造两个
内容不同但哈希相同的文件来骗过秒传，让用户下载到错误的文件。
代价是摘要从 32 位十六进制变成 64 位，换来抗碰撞——这个交换是划算的。

### 7. 分片上传的 fileId 来自客户端，必须校验

`fileId` 是 `init` 发给前端、前端再传回来的，它会被直接拼进文件路径。
如果不校验形状，传一个 `../../` 构造的 id 就能把分片写到存储根目录之外。

```java
private static final Pattern SAFE_ID = Pattern.compile("^[0-9a-fA-F]{8}-...$");
```

服务端只认自己发出去的那种 UUID 形状，其它一律拒绝（`LocalDiskStorageTest` 里有 6 个用例专门打这个）。
成品路径同理，`resolveSafe()` 会检查拼出来的路径仍在 `files/` 之内。

### 8. 成品按日期分目录

存成 `files/yyyy/MM/dd/{fileId}`，而不是所有文件平铺在一层。
单个目录文件数上万之后，目录遍历和 `ls` 都会明显变慢。
路径里**不拼原始文件名**——原始文件名可能含空格、中文、甚至 `../`，
而且同一个 fileId 天然唯一，不需要靠文件名区分。

### 9. 为什么用 `ATOMIC_MOVE`

分片落盘先写 `N.part.incoming` 再原子改名成 `N.part`。
直接往目标路径写的话，进程在中途被杀就会留下一个**看起来像完整分片、其实是半截**的文件，
合并时神不知鬼不觉地拼出坏文件。改名是原子的，要么没有、要么完整。

`put()` 里对 `ATOMIC_MOVE` 做了降级：跨盘符移动时文件系统不支持原子移动，
此时退化成普通 `move` 并打日志。

### 10. 完成动作的幂等

```sql
UPDATE file_info SET storage_path = #{path}, status = 1
WHERE file_id = #{fileId} AND status = 0
```

带 `status = 0` 条件，两个请求同时点「完成」只有一个能改成功，另一个影响 0 行、
直接跳过后续清理。前端重试、网络抖动重复提交都不会把状态改坏。

### 11. 分片级校验：错误要在最小的粒度上暴露

只有整文件哈希的话，传坏一片的代价是"合并完才发现，然后 2GB 全部重来"。
所以每片上传时客户端带 `chunkHash`，服务端边写边用 `DigestInputStream` 复算：

- 摘要或长度对不上 → 删掉本次的 `.incoming`，返回 **30004**，客户端只重传这一片；
- **不覆盖旧分片**：旧片可能是好的，坏的是这次收到的字节，不能因为一次坏重传把已经正确的数据弄丢；
- 落盘成功才把 `chunk_hash` 写进 `file_chunk`，供合并阶段再核对一次。

合并时对每片再复算一遍 SHA-256（和整文件摘要共用同一次磁盘读），发现坏片就只删这几片、
返回 **30005**。客户端收到后重新拉 `/status`，把缺失序号补上后再合并，最多自愈 2 轮。

这解决的是两类不同的问题：30004 拦"传输过程中坏掉的片"，30005 拦"落盘之后被改坏/截断的片"。

### 12. 为什么合并要按磁盘对账、状态接口也按磁盘对账

`/api/file/{id}/status` 回的是 `listChunksOnDisk()` 的结果，不是 `file_chunk` 表。
暂停、刷新、坏片补传之前，客户端都先和这个接口对齐一次——**数据库是索引，磁盘才是事实**
（见取舍 2）。如果按表对账，一个"表里有、磁盘上已经被清理掉"的片会让客户端误以为不用传，
合并时才会报缺片，白走一趟。

### 13. Range 下载：断点续传不只是上传

`GET /api/file/{id}/download` 支持单段 `Range: bytes=start-end`，返回 **206** +
`Content-Range` + `Accept-Ranges: bytes` + `ETag`（内容哈希）。
不可满足的范围返回 **416** + `bytes */size`；多段 Range 按 RFC 允许直接回整文件，
因为 multipart/byteranges 的收益不足以抵消实现复杂度。
响应体用 `BoundedInputStream` 卡住剩余字节数，读完即 EOF，不会把整个文件塞进内存。

### 14. 过期任务清理：判据是 update_time

`@Scheduled` 每 10 分钟扫一次 `status = 0` 且 `update_time` 超过
`file-hub.upload-expire-minutes` 的任务，删分片目录 + `file_chunk` 行 + `file_info` 行；
同时清理 `tmp/*.merging` 和 `chunks/**/*.part.incoming` 里超过同样时长的残留文件。

用 `update_time` 而不是 `create_time`：20GB 的文件传几个小时是正常的，只要还在传分片，
`touchUploading` 就会刷新时间；只有长时间一片都没落盘的任务才算废弃。

### 15. 前端为什么要自己实现增量 SHA-256

`crypto.subtle.digest()` 要求把整个文件读进一个 `ArrayBuffer`，2GB 文件就是 2GB 内存。
`web/src/lib/sha256.js` 是支持 `update()` 的增量实现，每次只喂 4MB；分片 SHA-256 则用
WebCrypto（每片只有 5MB，内存可控，而且硬件加速更快）。
增量实现已用 Node `crypto` 对 12 组长度（含 55/56/63/64 等 padding 边界）做过全量 + 不规则分块喂入校验。

---

## 表结构为什么长这样

```sql
-- 秒传的查询条件就是 (file_hash, file_size, status)，做成联合索引直接命中
KEY idx_hash_size_status (file_hash, file_size, status)
-- 删除时按 storage_path 数引用行数
KEY idx_storage_path (storage_path)

-- 分片表：靠唯一索引把"重复上传同一分片"变成幂等操作
UNIQUE KEY uk_file_chunk (file_id, chunk_index)
```

---

## 跑起来

```bash
# 1. 建库建表
mysql -uroot -p < src/main/resources/schema.sql

#    已经跑过 v1 的库，只需补一列（脚本幂等，重复执行不会报错）：
mysql -uroot -p < scripts/migrate-v2-chunk-hash.sql

# 2. 数据库口令走环境变量，不落盘（application.yml 里写的是 ${MYSQL_PWD}）
export MYSQL_PWD=你的口令

# 3. 起服务（静态工作台已经打进 jar，不需要单独起前端）
mvn clean package -DskipTests
java -Dfile.encoding=UTF-8 -jar target/file-hub-1.0.0.jar
# → http://127.0.0.1:8081         Vue 工作台
# → http://127.0.0.1:8081/classic.html   原始单页演示
```

### 改前端

```bash
cd web
npm install          # 只有改前端时才需要
npm run dev          # Vite dev server，/api 代理到 127.0.0.1:8081
npm run build        # 产物直接写进 ../src/main/resources/static/
```

`vite.config.js` 里 `build.outDir` 指向 Spring Boot 的静态目录，并且 `emptyOutDir: true`；
原始演示放在 `web/public/classic.html`，构建时会原样复制过去。

### 主要接口

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/file/config` | 分片大小、存储后端标识 |
| GET | `/api/file/check?hash=&size=` | 秒传预检 |
| POST | `/api/file/init` | 秒传 / 复用上传中任务 / 新建，返回已传分片 |
| POST | `/api/file/chunk` | `fileId` + `index` + 可选 `chunkHash` + 分片 |
| GET | `/api/file/{id}/status` | 以磁盘为准的已传分片（断点续传对齐） |
| POST | `/api/file/{id}/complete` | 逐片 + 整文件校验、流式合并（30005 表示坏片已剔除） |
| GET | `/api/file/list` / `/api/file/stats` | 文件列表 / 秒传节省统计 |
| GET | `/api/file/{id}/download` | 流式下载，支持单段 `Range` |
| DELETE | `/api/file/{id}` | 删除逻辑记录，最后一个引用才删物理文件 |

Windows 下 `-Dfile.encoding=UTF-8` 不能省：不指定时 SnakeYAML / 日志会按 GBK 处理中文。

| 配置项 | 默认 | 说明 |
|---|---|---|
| `file-hub.root` | `D:/Projects/file-hub-data` | 存储根目录 |
| `file-hub.chunk-size` | 5MB | 分片大小，前端按它切片 |
| `spring.servlet.multipart.max-file-size` | 512MB | 单分片上限，给足余量 |

### 验证

```bash
mvn test                      # 23 个单元测试（含分片摘要/大小拒绝、临时文件清理）
bash scripts/e2e-test.sh      # 端到端验收（真传 120MB，逐项断言）
bash scripts/bench.sh 1000 4  # 基准测试，输出 markdown 表格
```

> `e2e-test.sh` 在 v1 的 25 项基础上补了分片摘要拒绝（30004）、合并时坏片自愈（30005）、
> `/status`、`/stats` 和 Range 下载的断言。**2026-11-18 已在真实 MySQL 8.0.45 上跑通：
> 45 项断言全部通过**（临时实例、端口 3307、独立存储目录，未动本机 3306 的库）。
> 跑它需要一个已建库的环境，且脚本和服务端在同一台机器上（坏片用例要直接改磁盘分片）。

---

## 实测数据

环境：Windows 11 / JDK 17 (Corretto) / MySQL 8.0.45 本机 / 单实例，分片 5MB，上传并发 4。

| 文件大小 | 分片数 | 分片上传 | 服务端合并 | 合计 | 端到端吞吐 |
|---|---|---|---|---|---|
| 300 MB | 60 | 3.07 s | 1.35 s | 4.43 s | **67.8 MB/s** |
| 1000 MB | 200 | 10.02 s | 4.67 s | 14.69 s | **68.1 MB/s** |

- 两次下载回来的文件 SHA-256 与原文件**完全一致**——这是"分片拼对了"的唯一硬证据。
- 秒传：**服务端单次约 10 ms**；客户端观感 552 ms，其中约 150 ms 是 Windows 上
  两次 `curl.exe` 进程启动的开销（实测纯 curl 往返约 75 ms/次，服务端处理约 5 ms）。
- 1GB 的文件走秒传，**传输量为 0**（200 个分片全部不用传）。

> 吞吐 68 MB/s 的量级说明：瓶颈基本在本机磁盘 IO 和 sha256sum/curl 这些客户端进程上，
> 不是服务端逻辑。这个数字的意义是"链路是通的、没有明显瓶颈"，不是"性能上限"。
>
> 这组数据是 v1（合并时只算整文件摘要）测的。v2 在合并时多算一次逐片 SHA-256，
> 但和整文件摘要共用同一次磁盘读，理论开销主要多的是 CPU 摘要计算，没有重新跑基准。

---

## 已知不足（别在面试里吹过头）

- **整文件哈希仍跑在主线程**：虽然改成了 4MB 增量、内存恒定，但 1GB 文件算哈希时页面仍会
  有可感知的卡顿。真实产品会用 Web Worker；哈希结果会缓存进 localStorage，第二次选择同一文件
  可以直接跳过。缓存失效时服务端会返回 30003，界面提示重新计算，不会静默传坏文件。
- **刷新页面不会自动续传**：浏览器拿不到刷新前的 `File` 对象，需要用户重新选择同名、同大小、
  同修改时间的文件，前端用指纹（name + size + lastModified）对上本地断点记录后再续传。
- **单实例**：分片落本地磁盘，多实例部署时 `init` 和 `chunk` 可能落到不同机器上就找不到分片了。
  要水平扩展得把分片也放到共享存储（OSS/S3）或按 `fileId` 做一致性哈希路由。
- **没有做认证**：任何能访问到接口的人都能上传/下载/删除。真实系统这里要接鉴权，
  并且按用户隔离可见性。
- **没有限流**：单个客户端可以打满磁盘 IO。
- **Range 只支持单段**：多段 Range 按 RFC 允许的简化路径直接返回整文件，没有实现
  `multipart/byteranges`。
- **端到端脚本依赖本机环境**：坏片自愈用例要直接改服务端磁盘上的分片文件，所以脚本必须和
  服务端同机跑；脚本也没有清理它创建的测试数据，重复跑会留下"上传中"记录。
  基准数据仍是 v1 的，v2 多了逐片摘要计算，但没有重跑 `bench.sh`。
- **清理任务与暂停冲突**：一个任务暂停超过 `upload-expire-minutes` 后被回收，用户再点继续时
  `init` 会新建任务，需要从头传（数据是安全的，只是省不掉重传）。

---

## 我需要能回答的追问

（这是我自己整理的，面试前会过一遍）

1. 为什么分片大小选 5MB？调大调小各有什么影响？
   → 调小：请求数变多、HTTP 开销占比高、DB 行数暴涨；调大：单片失败的重传代价大、
   内存和超时风险高。5MB 是个常见折中。
2. 为什么不用 WebCrypto 算 SHA-256？
   → `crypto.subtle.digest()` 要求把整个文件读成一个 `ArrayBuffer`，
   2GB 文件就是 2GB 内存。所以手写了一份支持 `update()` 的增量实现，
   每次只喂 4MB 切片（`web/src/lib/sha256.js` 里的 `Sha256` 类，12 组长度对齐过 Node 的 `crypto`）。
3. 两个用户同时传同一个文件会怎样？
   → 都错过秒传预检，各传各的；先合并完的写入存储，后合并完的发现已有同内容成品，
   删掉自己的临时文件复用对方的。最终磁盘上只有一份（设计取舍第 5 条）。
4. 合并到一半服务挂了会怎样？
   → 状态还是"上传中"，分片都还在磁盘上。重新 `init` 会复用这个任务并回传已传分片，
   补完剩下的再合并。不会产生半个成品文件——成品是最后一步 `ATOMIC_MOVE` 进去的。
5. 为什么判缺片要扫磁盘而不是查表？
   → 见设计取舍第 2 条。
6. 这个 `StorageBackend` 接口换成 OSS 要改哪些地方？
   → 只加一个实现类 + 一行 Bean 配置，`FileStorageService` 不动。
   但要注意 OSS 的 `put` 是上传而非本地移动，以及 OSS 没有真正的"目录"。
7. 用 MD5 行不行？
   → 见设计取舍第 6 条。
8. 分片已经传完，合并时才发现其中一片坏了，怎么办？
   → 合并时逐片复算摘要（与整文件摘要共用一次读取），只删坏片、返回 30005；
   客户端拉 `/status` 对齐后只补这几片，最多自愈 2 轮。见设计取舍第 11 条。
9. 为什么分片重传时"校验失败"不覆盖旧分片？
   → 旧片可能本来就是好的，坏的是这次收到的字节。删掉本次 `.incoming` 即可，
   不能让一次坏重传把已经正确的数据也弄丢。
10. 断点续传的状态以数据库还是磁盘为准？
   → 磁盘。`/status` 和合并前的缺片判断都扫磁盘目录；数据库是索引，可能和磁盘不一致。
   见设计取舍第 2、12 条。
