# file-hub

一个自己实现存储层的文件服务：**分片上传 / 秒传 / 断点续传 / 分片级校验与自动补传**，
外加一个 Vue 3 + Vite 的可视化传输工作台，以及**按账号隔离的鉴权与限流**。

和"调一个 OSS SDK 把文件传上去"不同，这个项目把对象存储那一层的语义自己写了一遍——
内容寻址、分片状态、合并的原子性、并发去重——所以它替换掉本地磁盘实现就能接 OSS/MinIO，
而不是把业务代码绑死在某个云厂商的 SDK 上。

前端构建产物直接打进 `src/main/resources/static/`，**一个 jar + 一个静态工作台**，
不需要额外起 Node 服务；原来的单页演示保留在 `/classic.html`。
运维侧带 Dockerfile / compose（单机与双实例）、Actuator + Prometheus 指标、
请求 ID 与 JSON 日志、OpenAPI 文档，见下文「运维」和「Docker 一键启动」。
另外提供 **Windows 桌面安装包**：内置 H2 数据库，双击即用，不需要 MySQL/Docker，
见「Windows 桌面版」。

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
     │  ① 在 Web Worker 里增量算整文件 SHA-256，结果缓存进 localStorage
     │  ② POST /api/file/init       → fileId + 已传分片序号 + 是否秒传命中
     │  ③ POST /api/file/chunk      ×N，并发 4；每片附带 WebCrypto 算的 SHA-256
     │  ④ 失败分片指数退避重试；暂停/刷新后从 /status 或 init 重新对齐
     │  ⑤ POST /api/file/{id}/complete  逐片复算 + 整文件复算 → 流式合并 → 原子落盘
     │  ⑥ 30005 坏片 → 只补坏片（最多 2 轮自愈）；下载支持 HTTP Range
     ▼
  ┌──────────────────────────────────────────────┐
  │  Spring Security + Bearer Token + 限流        │
  │  （/api/** 需登录；ADMIN 可跨用户扫描）       │
  ├──────────────────────────────────────────────┤
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

带 `status = 0` 条件，两个请求同时点「完成」只有一个能改成功，另一个影响 0 行。
数据库条件是最后一道防线；单实例内还用同一把文件写锁把整个合并过程串起来，
后到的请求进来时会看到 `status = 1` 并直接返回，不会重复读 2GB 分片再拼一遍。

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

`GET /api/file/{id}/download` 支持 `Range`：

- **单段** `bytes=start-end`：**206** + `Content-Range` + `Accept-Ranges: bytes` + `ETag`
  （内容哈希）；用 `FileChannel.position()` 直接定位，不会从文件头开始空读。
- **多段** `bytes=0-99,1000-1099`：**206** + `Content-Type: multipart/byteranges; boundary=…`，
  每段带自己的 `Content-Range`，`Content-Length` 在发送前精确算出（不依赖 chunked）。
  上限是 8 段、总计 64MB，超限或语法非法统一回 **416** + `bytes */size`，
  不会「悄悄回退成整文件」——那会让客户端按 206 解析却收到 200 的语义。
- 多段响应由 `SequenceInputStream` 流式拼出，打开失败时会关闭已经打开的流；
  单个文件依然只读取请求区间，不会整个进内存。

响应体用 `BoundedInputStream` 卡住剩余字节数，读完即 EOF。

### 14. 过期任务清理：判据是 update_time，删除要带条件

`@Scheduled` 每 10 分钟扫一次 `status = 0` 且 `update_time` 超过
`file-hub.upload-expire-minutes` 的任务，删分片目录 + `file_chunk` 行 + `file_info` 行；
同时清理 `tmp/*.merging`、`chunks/**/*.part.incoming` 和没有对应任务的孤儿分片目录。

用 `update_time` 而不是 `create_time`：20GB 的文件传几个小时是正常的，只要还在传分片，
`touchUploading` 就会刷新时间；只有长时间一片都没落盘的任务才算废弃。

清理不是"查到就删"：查询和拿锁之间，可能有分片刚好提交并刷新了时间。所以删除用条件 SQL：

```sql
DELETE FROM file_info
WHERE id = #{id} AND status = 0
  AND update_time < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)
```

影响 0 行就说明任务已经复活/完成，直接跳过，不能顺手把它的分片删掉。
任务被删掉之后才到达的迟到分片，`saveChunk` 会发现 `touchUploading` 影响 0 行，
把刚写的分片和元数据回滚掉，不让它变成没有 `file_info` 行的孤儿。

用户重新 `init` 续传时也会刷新一次 `update_time`——主动回来续传本身就是心跳，
避免刚打开页面任务就被回收。超过期限一直没回来的，仍然会被清理，这时续传只能从头传。

### 15. 并发一致性：按文件分段的读写锁

几个操作会同时碰同一个文件的磁盘和数据库：并发分片上传、合并、删除、定时清理。
这里用 `FileLockRegistry` 做单实例内的分段读写锁：

- **分片上传拿读锁**：4 个并发分片互不阻塞；
- **合并 / 删除 / 清理拿写锁**：必须等正在落盘的分片结束，之后再进来的分片也能看到任务已结束；
- **秒传插入、删除最后一条引用、合并后去重拿同一把内容锁**：防止 `countByStoragePath` 数到 0
  的同时，秒传刚好插入一条指向同一物理文件的新记录；
- 锁按 key 哈希到 256 个槽位，不为每个任务建一个锁对象，避免任务多了以后锁无限增长。

必须强调：`FileLockRegistry` 是**进程内锁**，只在单实例部署下完整成立。多实例时把
`file-hub.cluster.enabled` 打开，换成 `RedisLockRegistry`（Redisson `RReadWriteLock`），
所有锁点、key 和语义都不变，只是从 JVM 内换成了跨 JVM 的 Redis 锁。Redis 异常时业务明确失败，
不会退回本地锁。

一个容易踩的细节：**不能在锁里包 `@Transactional` 再在方法返回时释放锁**。
Spring 的事务提交发生在方法返回之后，锁已经放了、事务还没提交，第二个线程会读到旧状态。
所以这些方法去掉了 `@Transactional`，让每次 mapper 调用独立提交，锁释放时状态已经可见；
需要原子性的地方（`markDone`、条件删除）靠 SQL 自身的 `WHERE` 条件兜底。

合并失败时的处理也保持一致：30005 只删坏片并删掉对应 `file_chunk` 行，不再出现
"事务回滚把数据库行恢复了、磁盘上的坏片却已经删了"的不一致。
老任务（v2 之前 `chunk_hash` 为 NULL）在整文件校验通过后回填逐片摘要，
下次自愈就有定位依据；校验没通过时不回填，不会把坏片"洗白"。

### 16. 前端为什么要自己实现增量 SHA-256

`crypto.subtle.digest()` 要求把整个文件读进一个 `ArrayBuffer`，2GB 文件就是 2GB 内存。
`web/src/lib/sha256.js` 是支持 `update()` 的增量实现，每次只喂 4MB；分片 SHA-256 则用
WebCrypto（每片只有 5MB，内存可控，而且硬件加速更快）。
增量实现已用 Node `crypto` 对 12 组长度（含 55/56/63/64 等 padding 边界）做过全量 + 不规则分块喂入校验。

### 17. 「记录在、文件没了」也要当成错误处理

秒传如果只信数据库，会踩一个隐蔽的坑：`file_info` 有一条 `status = 1`、`storage_path = X` 的记录，
但 `files/X` 被手工清理、备份恢复漏了或磁盘坏了。此时秒传会命中这条记录，用户拿到一个 0 字节的
"成功"，下载时才 500。

现在的规则是**每次复用已完成记录前，先确认物理文件存在且大小一致**（`isStorageIntact`）：

- `check` / 秒传去重时逐条检查候选记录，跳过坏记录继续找可用的；全都不可用就退回正常上传；
- 已完成的 `complete` 重入、下载入口也做同样检查，分别返回 **20007 / 20006**，而不是假装成功；
- 坏记录保留下来供人工排查，不会自动删掉——自动删可能误伤"存储暂时不可达"。

反向的孤儿也要收：`files/` 下存在、但没有任何 `status = 1` 记录引用的文件，超过
`orphan-file-grace-hours`（默认 7 天）后由定时任务回收。删除前拿该文件的写锁并重新数一次引用，
确认仍是 0 才动磁盘；给 7 天宽限期，是为了避免数据库临时不可用、配置指错库时把仍在使用中的
文件当孤儿删掉。

---

## 表结构为什么长这样

```sql
-- 秒传的查询条件就是 (file_hash, file_size, status)，做成联合索引直接命中
KEY idx_hash_size_status (file_hash, file_size, status)
-- 删除时按 storage_path 数引用行数
KEY idx_storage_path (storage_path)
-- 定时清理的判据 (status = 0, update_time < cutoff)
KEY idx_status_update (status, update_time)
-- 列表按状态过滤后按 id 倒序分页
KEY idx_status_id (status, id)

-- 分片表：靠唯一索引把"重复上传同一分片"变成幂等操作
UNIQUE KEY uk_file_chunk (file_id, chunk_index)

-- 分享：token 原文存库（「我的分享」要能重新拼出链接），它只是定位符；
-- 真正的口令是 4 位提取码，只存 BCrypt 摘要。token 用 ascii_bin，大小写敏感
UNIQUE KEY uk_share_token (share_token)   -- share_token 为 ascii_bin
KEY idx_share_owner (owner_id, id)
KEY idx_share_file (file_id)
KEY idx_share_status_expires (status, expires_at)
-- 清理任务分别按取消时间 / 过期时间扫
KEY idx_share_update_time (update_time)
KEY idx_share_expires (expires_at)
```

老库不用重建表：`scripts/migrate-v3-indexes.sql` 会先查 `information_schema.statistics`，
只在索引缺失时 `ALTER TABLE`，重复执行不会报错。

### 文件名搜索为什么用全文索引

`LIKE '%关键词%'` 用不上 B+Tree，文件名一多就是全表扫描。MySQL 8 内置的 ngram 解析器
能把中文切成二元组，所以 `file_info.file_name` 上建了
`FULLTEXT KEY ft_file_name (file_name) WITH PARSER ngram`。`list` 的关键词分支：

| 关键词 | 走法 |
|---|---|
| 完整 64 位十六进制 | `file_hash = ?` 精确命中（它就是 SHA-256） |
| ≥ 2 个字符 | `MATCH(file_name) AGAINST(? IN NATURAL LANGUAGE MODE)`，OR `file_id`/`file_hash` 的 LIKE（方便按短标识搜） |
| 1 个字符 | ngram 最小分词长度是 2，直接查不到，回退 `file_name LIKE` |

老库执行 `scripts/migrate-v5-fulltext.sql`（幂等）。注意：

- 需要 **MySQL 8**；5.7 没有 ngram parser。
- 大表上建全文索引会重建索引，可能阻塞写入，建议低峰执行。
- `MATCH` 和 `LIKE` 用 OR 组合时，MySQL 优化器不保证一定选全文索引；纯文件名关键词通常能命中，
  极短/极泛的关键词仍可能退化成扫描。这是「既要全文又要按 fileId 前缀搜」的取舍。

---

## 多用户与鉴权（v4）

- **不透明 Token，不是 JWT**：登录成功发一个 32 字节随机串（Base64URL），数据库只存它的
  SHA-256。Token 可以随时在服务端撤销、7 天自动过期；即使库泄露也拿不到可用的原始 Token。
- **账号级文件隔离**：`file_info.owner_id` 标记归属。普通用户的 list / check / status / download /
  delete 都只作用于自己的记录；访问别人的 `fileId` 统一返回 `10005`，不暴露「文件是否存在」。
  物理去重仍然是全局的：B 对 A 传过的同内容文件秒传时，会拿到**属于 B 的新 fileId**，
  不会复用 A 的记录。
- **旧数据**：`owner_id IS NULL` 的历史文件只有 ADMIN 可见。管理员默认列表是「自己的 + 旧数据」，
  传 `scope=all` 可以看全站；不会在迁移里猜一个所有者。
- **下载票据**：浏览器的 `<a href>` 没法带 `Authorization` 头，所以前端先调
  `GET /api/file/{id}/download-ticket` 换一张 5 分钟、绑定 file + user 的票据，再用
  `?ticket=…` 下载。票据同样只存哈希；curl/脚本仍可直接带 Bearer。
- **限流**：Spring MVC Interceptor 在鉴权之后执行。登录/注册按 IP，其他接口按用户；
  固定窗口计数（默认登录 10 次/分/IP、分片 2400 次/分/用户）。超限返回真实 HTTP 429 +
  `Retry-After` + 业务码 `40002`，前端会当作可重试。默认不信任 `X-Forwarded-For`，
  只有放在可信代理后面才打开。
- **管理员初始化**：启动时若设置了 `FILE_HUB_ADMIN_PASSWORD`（至少 8 个字符），
  会创建/提升 `FILE_HUB_ADMIN_USER`（默认 `admin`）为 ADMIN；密码不落盘、不打印。

鉴权错误用真实的 HTTP 状态码（401/403/429），但响应体仍然是统一的
`{code,msg,data}`，前端只需判断 HTTP 状态即可清理会话。

---

## 提取码分享（v8）

类似百度网盘的「链接 + 提取码」：上传者生成分享，别人拿到链接和 4 位提取码就能**免注册**下载。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/share` | `{fileId, expiresInDays}`，取值 `1/7/30/0`（0=永久），默认 7 天；仅文件所有者可创建 |
| GET | `/api/share/mine?page=1&size=20` | 我的分享（不返回提取码，库里只有 BCrypt 摘要） |
| DELETE | `/api/share/{token}` | 取消分享，链接立即失效 |
| POST | `/api/share/{token}/reset-code` | 换一个提取码，同时清零失败次数、解除锁定 |
| GET | `/api/public/share/{token}` | 匿名查询状态；只返回 `state/expiresAt/locked/lockedUntil`，**不返回文件名、大小、所有者** |
| POST | `/api/public/share/{token}/verify` | `{code}`，成功才返回文件名/大小和一张 5 分钟票据下载地址 |

- **链接形态**是 `index.html#/s/<43 位随机 token>`，用当前浏览器 origin 拼出来。反向代理部署时
  必须用对外域名访问，否则拼出来的是 `localhost`。
- **提取码** 4 位，字母表去掉了 `0/O/1/I` 等易混字符；输入会 `trim` + 转大写。库里只存 BCrypt
  摘要，明文只在创建/重置时返回一次。忘了只能重置，不能找回。
- **防爆破**：每个分享连续错 10 次锁 15 分钟（`file-hub.share.*` 可调），匿名接口再按
  IP + token 限流 30 次/分钟、按 IP 总量 120 次/分钟（token 写法由客户端控制，只按 token
  分桶可以被编码变体绕过）。4 位码只有约 20 bit 熵，靠这两层兜底，别把它当高安全的口令。
- **拖库防护（可选）**：设置环境变量 `FILE_HUB_SHARE_PEPPER` 后，BCrypt 摘要的是
  `HMAC-SHA256(code, pepper)`，密钥只在进程环境里。4 位码空间只有约 100 万，单靠 BCrypt
  被拖库后离线穷举很快；加 pepper 后光有数据库无法还原。修改该值会让已有分享的提取码全部失效。
- **创建配额**：单个文件最多 5 个有效分享、每个用户最多 100 个（`max-active-per-file` /
  `max-active-per-owner`），防止刷分享行和 BCrypt 编码拖垮 CPU。
- **已知取舍**：锁定是按分享的，拿到链接的人可以故意输错把分享锁 15 分钟。这是防爆破和
  可用性之间选的边；被恶意锁定时，所有者重置提取码即可立刻解锁。
- **下载完全复用现有链路**：校验通过后以**文件所有者**身份签发原有 5 分钟下载票据，所以 Range、
  多段 Range、流式下载的行为与登录下载一模一样，没有第二套下载代码。
- **失效语义**：取消/过期后新校验立即失败，但已经签发出去的票据最多还能再用 5 分钟；
  要即时吊销，需要下一版把 `share_id` 写进票据表并在下载入口复查。
- **桌面版**的分享链接是 `127.0.0.1:18080`，只能在同一台机器打开；界面会明确提示，
  跨机器分享请部署服务器版（Docker）。
- 服务器部署建议套 HTTPS：提取码和票据都在 URL/请求体里，明文 HTTP 在不可信网络上没有保密性。
  未配 HTTPS 时前端复制链接会回退到 `execCommand`，功能可用但仅限可信局域网。
- 已有 MySQL 库执行 `scripts/migrate-v6-share.sql`（只建新表，幂等）；桌面版 H2 启动时自动建表。
  定时任务每 30 分钟清理已取消/过期超过 7 天的记录和文件已不存在的孤儿分享。

错误码：`42001` 不存在或已取消、`42002` 已过期、`42003` 提取码错误（带剩余次数）、
`42004` 次数过多已锁定、`42005` 文件已失效、`42006` 提取码格式不对、`42007` 不是文件所有者、
`42008` 有效分享数量达到上限。

---

## 管理员完整性扫描与修复（v5）

数据库是索引、磁盘才是事实，所以磁盘被外部误删/改坏的记录迟早会和库对不上。管理员入口：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/integrity/scan?deep=false&limit=100` | 扫描 DONE 记录；`deep=true` 额外流式复算 SHA-256 |
| POST | `/api/admin/integrity/repair` | `{fileIds:[...]}`，删除**复检确认损坏**的逻辑记录 |

- 扫描按 `storage_path` 缓存结论：秒传共享的同一物理文件只 stat/读一次，但每条受影响的
  逻辑记录都会列出来，因为修复是按行删的。
- 浅扫描只查「存在 + 大小」，一次 stat 就能判断，适合大范围巡检；深扫描能发现「大小没变、
  内容被改坏」的静默损坏，代价是把文件全部读一遍。
- 修复前**重新检查**：从扫描到点修复之间文件可能被恢复、记录可能已被删。只有复检仍损坏才
  调删除；物理文件仍遵守引用计数，最后一个引用消失才真删盘。
- 普通用户访问 `/api/admin/**` 返回 403；Vue 工作台只有 ADMIN 能看到顶部「完整性」按钮。

---

## 多实例部署（v5：共享目录 + Redis 分布式锁）

默认 `file-hub.cluster.enabled=false`：分片锁是 JVM 内的分段读写锁，限流是 Caffeine，
**不需要 Redis**，行为和之前的单机版完全一致。

打开 `file-hub.cluster.enabled=true` 后，锁和限流同时切到 Redis：

- **分布式锁**：Redisson `RReadWriteLock`，key 为 `filehub:lock:<原来的 key>`（fileId /
  `content:<hash>:<size>` / `cleanup:scheduler` / `gc:physical`）。`lock()` 不设固定租期，
  由 watchdog 自动续期——合并大文件可能远超任意固定租期。Redis 不可用时抛 `20008` 明确失败，
  **不会静默退回本地锁**（否则两个实例会同时写同一份物理文件）。
- **限流**：Lua 脚本把 `INCR` 和 `PEXPIRE` 放在一次调用里，登录按 IP、上传按用户，
  两个实例共享同一份计数；Redis 故障返回 HTTP 503 + `50003`，同样不静默放行。
- **清理抢主**：定时清理外层 `tryWrite("cleanup:scheduler")`，抢不到锁的实例直接跳过。
  孤儿成品 GC 与「秒传插入 / 合并去重复用」通过 `gc:physical` 读写锁互斥，锁顺序固定为
  file/content → gc。
- **共享目录**：所有实例的 `file-hub.root` 必须指向同一个**支持原子 rename、mtime 一致**的
  目录（NFS/SMB 请先在目标环境验证）。分片、合并临时文件、成品都在共享盘上，任一实例都能
  查询和下载另一个实例写入的分片/成品。
- **会话**：Token 存数据库，注册/登录在任何节点都有效，不需要粘性会话。

两个实例的启动方式（同一个 MySQL、同一个 root、同一个 Redis）：

```bash
export MYSQL_PWD=你的口令
java -Dfile.encoding=UTF-8 -jar target/file-hub-1.0.2.jar \
  --server.port=8081 \
  --file-hub.root=/shared/file-hub-data \
  --file-hub.cluster.enabled=true \
  --file-hub.cluster.redis-address=redis://127.0.0.1:6379

java -Dfile.encoding=UTF-8 -jar target/file-hub-1.0.2.jar \
  --server.port=8082 \
  --file-hub.root=/shared/file-hub-data \
  --file-hub.cluster.enabled=true \
  --file-hub.cluster.redis-address=redis://127.0.0.1:6379
```

---

## 运维：健康检查、指标、日志、接口文档（v6）

| 端点 | 权限 | 说明 |
|---|---|---|
| `GET /actuator/health` | 公开 | 汇总健康状态；`storage` 检查 root 可写、集群模式下 Redis 可连 |
| `GET /actuator/health/liveness` / `readiness` | 公开 | 容器探针 |
| `GET /actuator/metrics`、`/actuator/prometheus` | ADMIN | Micrometer 指标（Prometheus 文本格式） |
| `GET /swagger-ui.html`、`/v3/api-docs` | 公开（演示） | OpenAPI 文档；生产建议只放内网或改成 ADMIN |
| `GET /actuator/info` | 公开 | 应用名 / 版本 |

业务指标都在 `filehub.*` 命名空间下：

- `filehub.upload.instant_hits`、`instant_bytes`：秒传命中次数，以及“省下”的字节数；
- `filehub.chunk.saved`、`chunk.bytes`：成功落盘的分片数与字节数；
- `filehub.upload.completed`、`merge_bytes`、`merge_duration`：合并成功与耗时；
- `filehub.upload.merge_failed{code=…}`：按业务码区分（缺片 30001 / 摘要不符 30003 / 坏片 30005）；
- `filehub.cleanup.*`、`filehub.rate_limit.rejected{scope}`：清理回收数量、限流拒绝次数。

```bash
curl -s http://127.0.0.1:8081/actuator/health
TOKEN=<管理员 Token>
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8081/actuator/prometheus | head
```

日志与链路：

- 每个请求都有 `X-Request-Id`：调用方传的合规 ID 会复用，否则服务端生成；同一值回写响应头。
  控制台格式里是 `rid=…`，JSON 格式里是 `requestId` 字段。ID 只接受 1–64 位
  `[A-Za-z0-9._-]`，避免往日志里注入换行。
- `traceId` / `spanId` 由 Micrometer Tracing (Brave) 生成并写入 MDC；当前没有配置 exporter，
  只在本地做日志关联，不往外部发送。
- 默认是人读的 console 格式；需要一行一条 JSON 时用 `--spring.profiles.active=json`
  （容器里设 `SPRING_PROFILES_ACTIVE=json`）。
- 日志不记录 `Authorization`、Token、下载票据；RequestIdFilter 在 Spring Security 之前执行，
  401/403/429 的响应也带同一个请求 ID。

## Docker 一键启动（v6）

单机（MySQL 8 + Redis 7 + app，数据都在命名卷里）：

```bash
export MYSQL_ROOT_PASSWORD='MySQL root 口令（只给 healthcheck 用）'
export MYSQL_APP_PASSWORD='应用连库口令'
export FILE_HUB_ADMIN_PASSWORD='管理员初始口令'
docker compose up --build
# → http://127.0.0.1:8081
```

应用**不用 root 连库**：MySQL 官方镜像会用 `MYSQL_USER` / `MYSQL_PASSWORD` 创建
`filehub` 用户并只授权到 `file_hub` 库。三个口令都只从环境变量注入。

多实例（8081/8082 两个 app，共用一个 MySQL、一个 Redis、一份 `file-data` 共享卷）：

```bash
docker compose -f docker-compose.cluster.yml up --build
```

- 镜像多阶段构建：Maven 阶段 `package -DskipTests`，运行阶段用 JRE + 非 root 用户，
  存储根目录固定为 `/data` 卷。
- 两个口令只从环境变量注入，compose 文件里没有明文；数据库端口没有映射到宿主机。
- `schema.sql` 里含 `DROP TABLE IF EXISTS`，但只会在 MySQL 首次初始化**空数据卷**时执行一次；
  已有数据请用 `scripts/migrate-v*.sql`，不要删卷重来。
- 前端产物已经提交在 `src/main/resources/static`，镜像里不需要再跑 npm。

---

## Windows 桌面版（v7）

给不想装 MySQL/Docker 的人用的单机版：同一个 Spring Boot + Vue 应用，内置 **H2 文件数据库**，
只监听 `127.0.0.1:18080`，在独立窗口中打开界面（WebView2，不依赖默认浏览器）。
服务器 / Docker 模式完全保留，两种形态互不影响。

### 安装与使用

1. 运行 `FileHub-1.0.2.msi`（构建产物在 `build/desktop/dist/`）。
   按用户安装，安装时可以改目录（默认 `%LOCALAPPDATA%\FileHub`），不需要管理员权限；
   开始菜单和桌面会创建快捷方式。
2. 双击 `FileHub`，程序在独立窗口中打开 `http://127.0.0.1:18080`，不会占用默认浏览器。
3. 第一次使用先注册。**第一个注册的账号自动成为管理员**（桌面版单人使用，
   不需要额外配置管理员密码）。
4. 关闭窗口时会弹窗确认：可以「最小化到托盘」继续运行，或「直接退出」。
   最小化后双击托盘图标恢复窗口；托盘菜单里还可以打开数据目录、退出程序。
5. 重复双击只会把已有窗口调到前台，不会启动第二个进程
   （外壳单实例互斥量 + 数据目录文件锁）。
6. 从旧版升级：直接运行新版本 MSI 即可，会替换旧版程序、保留
   `%LOCALAPPDATA%\FileHubData` 里的账号和文件。**新包的版本号必须高于旧版**
   （MSI 的 ProductVersion）；同版本号重装只会走修复流程，不会替换成新界面。

数据全部在 `%LOCALAPPDATA%\FileHubData`（**与安装目录 `%LOCALAPPDATA%\FileHub` 分开**）：

```
db/filehub.mv.db      H2 数据库（账号、会话、文件元数据）
storage/files/        合并后的成品文件（按年/月/日分层）
storage/chunks/       上传中的临时分片
logs/filehub.log      滚动日志（排障用）
```

- **备份**：关掉程序后复制整个 `%LOCALAPPDATA%\FileHubData` 目录。
- **卸载**：Windows 设置里卸载只删程序目录，数据目录保留；要彻底清空就手动删除
  `%LOCALAPPDATA%\FileHubData`。
- **升级**：新版本 MSI 直接覆盖安装，数据库文件继续复用。
- 桌面版关闭了 Swagger/OpenAPI（本地单机不需要暴露接口文档），
  关键词搜索没有 MySQL ngram，自动回退 `LIKE`。
- 未做代码签名，Windows SmartScreen 会提示“未知发布者”：
  点「更多信息 → 仍要运行」即可。这是预期行为。
- 只监听回环地址，不能给局域网/公网访问；H2 和文件都是明文的，
  安全性依赖 Windows 用户目录权限。需要多用户/远程访问请用服务器模式。
- 系统要求：Windows 10/11 x64。界面依赖 **Microsoft Edge WebView2 Runtime**
  （Windows 11 和较新的 Windows 10 一般自带；缺失时启动会提示官方下载地址），
  外壳依赖 **.NET Framework 4.8**（Windows 10 1903+ / Windows 11 自带）。
  这两个都是系统组件，不需要用户装 Java。

### 构建安装包

前置：JDK 17、Node/npm、Maven、Windows 自带的 .NET Framework 4.x（`csc.exe`）；
MSI 还需要 WiX Toolset 3.x（`candle.exe` / `light.exe` 在 PATH，或设置 `WIX_BIN` 指向其 `bin` 目录）。
本机用官方 `wix314-binaries.zip` 解压到 `D:/Tools/wix314` 即可，不需要管理员安装。
WebView2 SDK（NuGet）由脚本按固定版本下载并缓存到 `build/shell/cache`。

```bash
# 前端 build → jar → jlink 运行时 → app-image → WebView2 外壳 → MSI
WIX_BIN=D:/Tools/wix314 bash scripts/package-desktop.sh

# 产物
#   build/desktop/dist/FileHub/FileHub.exe        免安装目录（可整体拷贝）
#   build/desktop/dist/FileHub-1.0.2.msi           安装包
```

安装目录里有两个 exe：`FileHub.exe` 是 WebView2 外壳（开始菜单/桌面快捷方式指向它），
`FileHubBackend.exe` 是 jpackage 原启动器（外壳用管道启动它，关窗口时关管道让它优雅退出）。
脚本把 app-image 的启动器改名并补一份 `app/FileHubBackend.cfg`，再套上外壳，
最后用 `jpackage --app-image` 出 MSI，所以 MSI 的快捷方式自然指向外壳。
外壳的 `FileHub.exe.config`（Per-Monitor V2 DPI 缩放）由同一个脚本复制到安装目录。

脚本固定了 MSI 的 `UpgradeCode`，以后改版本号发布时升级会替换旧版本，
而不是并存两份。桌面版由 jpackage 自带的 JRE 启动，目标机器不需要装 Java。

### 发布给别人下载

把 `pom.xml` 的版本号和标签对齐后推 tag：

```bash
git tag v1.0.2
git push origin v1.0.2
```

`.github/workflows/release-desktop.yml` 会在 `windows-latest` 上跑单测、下载 WiX 便携版、
构建 MSI 和免安装 zip，并自动创建 GitHub Release——别人从 Releases 页面直接下载即可。
版本号与 tag 不一致时流水线会失败，避免发出名字对不上的包。
也可以在 Actions 页面手动 `workflow_dispatch`：只构建并保留产物，不创建 Release。

本机验证结果（2026-11-18）：

- `mvn test`：94 个测试全绿，其中 `DesktopDatabaseIntegrationTest` 用临时 H2 文件库
  跑了注册、大小写登录、分片重传、合并、LIKE 搜索、下载、过期任务清理。
- `npm run test:unit`：68 个前端单测全绿。
- 桌面 profile 的 `scripts/e2e-test.sh`：92 项全绿。
- 打包后的 `FileHub.exe`：自动建库、只监听 `127.0.0.1:18080`、托盘、日志、
  单实例、重启后账号仍在；MSI 元数据为 `FileHub 1.0.0` + 固定 `UpgradeCode`。
- MSI 已在本机做过完整安装/卸载冒烟：静默安装 → 开始菜单快捷方式 → 启动（4 秒内健康检查 200）
  → 注册首个账号得到 ADMIN → 登录（含用户名大小写不敏感、错误密码返回 40004）
  → 带 Token 访问列表成功、匿名请求被拒（40001）→ 卸载后程序目录和快捷方式消失、
  数据目录 `%LOCALAPPDATA%\FileHubData` 完整保留。
- ⚠️ 冒烟时发现并修掉一个真实问题：最初数据目录默认是 `%LOCALAPPDATA%\FileHub`，
  与 MSI 安装目录**同名同路径**，卸载会把用户上传的文件一起删掉。现在数据固定到
  `%LOCALAPPDATA%\FileHubData`，与安装目录分开。

本机验证结果（2026-11-19，WebView2 独立窗口版）：

- 从桌面快捷方式启动：独立 `FileHub` 窗口，没有浏览器标签页/地址栏，后端 5–6 秒内
  健康检查 200；登录态、管理员账号和原有 1 个文件（504.6 KB）在重装后完整保留。
- 点窗口 X：弹出「最小化到系统托盘 / 直接退出 / 取消」确认框。选择最小化后窗口隐藏、
  后端继续运行；再次双击快捷方式只恢复窗口（单例外壳，不会起第二套后端）。
- 选择直接退出：外壳关闭 stdin，后端读到 EOF 后优雅退出（`code=0`），端口释放、
  没有残留进程；外部强制结束外壳时同样走 EOF 优雅关闭。
- 导航限制：只允许 `http://127.0.0.1:18080`（按 `Uri` 校验 scheme/host/port，拒绝
  `http://127.0.0.1:18080@evil.com` 这类前缀绕过）；外部链接只交给默认浏览器打开
  http/https/mailto。
- 安装包内含 `FileHub.exe.config`，高 DPI/多显示器下按 Per-Monitor V2 缩放。
- 升级路径实测（2026-11-19）：先装 Release 里旧的 v1.0.0（浏览器版）到默认目录，
  再直接把版本号升到 1.0.1 的 MSI 覆盖安装（未先卸载）→ 退出码 0，注册表只剩一个
  FileHub 1.0.1、安装目录和快捷方式不变，旧的浏览器版文件被外壳/WebView2 文件替换；
  启动后独立窗口正常，管理员账号和原有文件仍在。**同版本号的新包无法这样升级**，
  必须提升 `pom.xml` 版本后重新打包。

### 桌面版的实现位置

- `src/main/resources/application-desktop.yml`：H2、回环端口、SQL 初始化、关闭 Swagger。
- `src/main/resources/schema-h2.sql`：H2 建表（幂等，启动时自动执行）。
- `src/main/java/com/yuzhuo/filehub/desktop/DesktopLauncher.java`：数据目录、单实例锁、
  启动 Spring、启动失败弹窗；单独运行时打开浏览器 + 托盘，被外壳启动时
  （`FILE_HUB_DESKTOP_SHELL=1`）改为监听 stdin EOF 并优雅关闭。
- `desktop-shell/FileHubShell.cs`：WebView2 独立窗口外壳（单实例、托盘、关闭确认、
  限制导航到本机地址、退出时关管道让后端优雅关闭）。
- `desktop-shell/app.manifest`：DPI 感知与 Windows 兼容性声明。
- `scripts/build-shell.sh`：下载 WebView2 SDK、编译外壳、替换 app-image 启动器。
- `scripts/package-desktop.sh`：打包脚本（app-image → 外壳 → MSI）。
- 普通 `java -jar` 不带 `-Dfilehub.desktop=true`，走的仍然是最初的 MySQL 服务器流程。

---

## 跑起来

```bash
# 1. 建库建表
mysql -uroot -p < src/main/resources/schema.sql

#    已经跑过 v1 的库，补一列 chunk_hash（脚本幂等，重复执行不会报错）：
mysql -uroot -p < scripts/migrate-v2-chunk-hash.sql
#    已经跑过 v2 的库，补列表/清理用的两个索引（同样幂等）：
mysql -uroot -p < scripts/migrate-v3-indexes.sql
#    已经跑过 v3 的库，补用户/会话/票据表和 owner_id（同样幂等）：
mysql -uroot -p < scripts/migrate-v4-auth.sql
#    已经跑过 v4 的库，补文件名 ngram 全文索引（需要 MySQL 8，同样幂等）：
mysql -uroot -p < scripts/migrate-v5-fulltext.sql
#    已经跑过 v5 的库，补提取码分享表（只建新表，同样幂等）：
mysql -uroot -p < scripts/migrate-v6-share.sql

# 2. 数据库口令走环境变量，不落盘（application.yml 里写的是 ${MYSQL_PWD}）
export MYSQL_PWD=你的口令

# 3. 起服务（静态工作台已经打进 jar，不需要单独起前端）
mvn clean package -DskipTests
# 管理员密码只从环境变量注入；不设置就不创建管理员，普通用户仍可注册
export FILE_HUB_ADMIN_PASSWORD='一个至少8位的口令'
java -Dfile.encoding=UTF-8 -jar target/file-hub-1.0.2.jar
# → http://127.0.0.1:8081         Vue 工作台（先注册/登录）
# → http://127.0.0.1:8081/classic.html   原始单页演示（共用同一份本机 Token）
```

curl 调用需要先拿 Token：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8081/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"filehub-demo-pass"}' | python -c \
  'import sys,json; print(json.load(sys.stdin)["data"]["token"])')

curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8081/api/file/list?page=1&size=10"
```

### 改前端

```bash
cd web
npm ci               # 按 package-lock.json 安装
npm run dev          # Vite dev server，/api 代理到 127.0.0.1:8081
npm run build        # 产物直接写进 ../src/main/resources/static/
npm run lint         # ESLint 9+ flat config（Vue recommended + Prettier 关格式冲突）
npm run format:check # Prettier：单引号、无分号、printWidth 100
npm run test:unit    # Vitest + Vue Test Utils + jsdom
npm run test:e2e     # Playwright（需要后端已在 8081 启动）
```

`vite.config.js` 里 `build.outDir` 指向 Spring Boot 的静态目录，并且 `emptyOutDir: true`；
原始演示放在 `web/public/classic.html`，构建时会原样复制过去。`test` 配置也在同一个文件里
（`environment: 'jsdom'`、`src/test/setup.js` 每个用例清空 localStorage）。

浏览器 E2E 默认用 Playwright 自带的 Chromium：

```bash
cd web
npx playwright install chromium     # 首次需要
npm run test:e2e
```

本机已装 Edge/Chrome 时可以用环境变量复用，省掉下载：
`PLAYWRIGHT_CHANNEL=msedge npm run test:e2e`。后端地址用 `PLAYWRIGHT_BASE_URL` 覆盖，
默认 `http://127.0.0.1:8081`。管理员用例需要启动服务时同样设置
`FILE_HUB_ADMIN_USER` / `FILE_HUB_ADMIN_PASSWORD`，否则自动跳过。

单元测试覆盖：SHA-256 与 Node `crypto` 对齐（含 55/56/63/64 字节边界和不等长分块）、
格式化边界、API 的 Bearer/401 清会话/429 可重试/超时/非 JSON、认证状态与订阅、
localStorage 按用户隔离、任务卡进度 aria、文件表搜索/分页/筛选，以及登录表单校验。
浏览器用例覆盖注册 → 刷新保持登录 → 退出 → 重新登录、错误密码提示、
上传小文件 → 列表刷新 → 搜索 → 票据下载 → 二次确认删除。

### 主要接口

除 `/api/auth/login`、`/api/auth/register`、`/api/file/config` 和静态资源外，
`/api/**` 都需要 `Authorization: Bearer <token>`。下载接口额外接受 `?ticket=`。

| 方法 | 路径 | 作用 |
|---|---|---|
| POST | `/api/auth/register` | 注册并直接返回 Token（可用 `allow-registration=false` 关闭） |
| POST | `/api/auth/login` | 登录，返回 Token + 用户信息 |
| GET | `/api/auth/me` | 当前用户与角色 |
| POST | `/api/auth/logout` | 撤销当前 Token，幂等 |
| GET | `/api/file/config` | 分片大小、存储后端标识（公开） |
| GET | `/api/file/check?hash=&size=` | 秒传预检（只在当前用户可见范围内找） |
| POST | `/api/file/init` | 秒传 / 复用上传中任务 / 新建，返回已传分片 |
| POST | `/api/file/chunk` | `fileId` + `index` + 可选 `chunkHash` + 分片 |
| GET | `/api/file/{id}/status` | 以磁盘为准的已传分片（断点续传对齐） |
| POST | `/api/file/{id}/complete` | 逐片 + 整文件校验、流式合并（30005 表示坏片已剔除） |
| GET | `/api/file/list?page=&size=&keyword=&status=&sort=&scope=` | 分页文件列表（`items` + `total`）；ADMIN 可用 `scope=all` |
| GET | `/api/file/stats?scope=` | 秒传节省统计；统计口径随账号隔离 |
| GET | `/api/file/{id}/download-ticket` | 换取 5 分钟下载票据（浏览器用） |
| GET | `/api/file/{id}/download` | 流式下载，支持单段/多段 `Range`；Bearer 或 `?ticket=` |
| DELETE | `/api/file/{id}` | 删除自己的逻辑记录，最后一个引用才删物理文件 |
| GET | `/api/admin/integrity/scan?deep=&limit=` | ADMIN：扫描损坏文件（存在性/大小，可选 SHA-256 复算） |
| POST | `/api/admin/integrity/repair` | ADMIN：`{fileIds}` 删除复检确认损坏的记录；普通用户 403 |

Windows 下 `-Dfile.encoding=UTF-8` 不能省：不指定时 SnakeYAML / 日志会按 GBK 处理中文。

| 配置项 | 默认 | 说明 |
|---|---|---|
| `file-hub.root` | `D:/Projects/file-hub-data` | 存储根目录 |
| `file-hub.chunk-size` | 5MB | 分片大小，前端按它切片（服务端会校验取值范围） |
| `file-hub.orphan-file-grace-hours` | 168（7 天） | 无任何数据库记录引用的成品文件，超过这个时长才回收 |
| `file-hub.auth.token-ttl-hours` | 168 | 登录 Token 有效期 |
| `file-hub.auth.ticket-ttl-minutes` | 5 | 下载票据有效期 |
| `file-hub.auth.allow-registration` | true | 是否开放自助注册 |
| `file-hub.cluster.enabled` | false | 开启后锁和限流切到 Redis（多实例） |
| `file-hub.cluster.redis-address` | `redis://127.0.0.1:6379` | 集群模式的 Redis 地址 |
| `file-hub.cluster.redis-database` | 0 | Redis database |
| `file-hub.cluster.key-prefix` | `filehub:` | 锁和限流 key 的统一前缀 |
| `file-hub.rate-limit.*` | 见 `application.yml` | 登录/注册/init/chunk/其他接口的固定窗口限额 |
| `MYSQL_URL`（环境变量） | 本机 3306 | 覆盖 JDBC URL；容器里指向 `mysql:3306` |
| `FILE_HUB_ROOT`（环境变量） | `file-hub.root` | 容器/共享目录部署时用，如 `/data` |
| `SERVER_PORT`（环境变量） | 8081 | 容器内监听端口 |
| `FILE_HUB_LOG_LEVEL`（环境变量） | debug | 生产/容器设 `info` 降噪 |
| `SPRING_PROFILES_ACTIVE=json` | 未启用 | 输出单行 JSON 日志（含 traceId/spanId/requestId） |
| `management.endpoints.web.exposure.include` | health,info,metrics,prometheus | Actuator 暴露的端点 |
| `spring.servlet.multipart.max-file-size` | 6MB | 单分片上限，只比分片大小大 1MB；改 `chunk-size` 时同步调整 |

### 验证

```bash
mvn test                      # 93 个单元测试（分片校验、并发锁、认证/Token/票据、限流、多段 Range、完整性扫描、请求 ID/健康检查）
cd web && npm run lint && npm run format:check && npm run test:unit && npm run build
                              # 前端：68 个单元/组件测试 + 生产构建
bash scripts/e2e-test.sh      # 单机端到端验收（真传 120MB，逐项断言，含跨用户隔离）
bash scripts/e2e-cluster.sh   # 多实例验收（两个 JVM + 共享目录 + Redis）
bash scripts/bench.sh 1000 4  # 基准测试，输出 markdown 表格
```

`e2e-cluster.sh` 默认连接 `127.0.0.1:8081` / `8082`，共享目录由 `FILE_HUB_ROOT` 传入。
它会验证：跨节点会话、分片交替写入、跨节点合并/下载、跨节点秒传、并发 complete、
引用计数删除，以及两个节点共享同一份登录限流计数。

单元测试不需要 MySQL；E2E 需要一个已建库的 MySQL 和服务端，并会注册两个随机用户做租户隔离用例。
设置 `FILE_HUB_ADMIN_PASSWORD`（启动服务时同一环境变量）还会验证管理员 `scope=all`、完整性扫描/
修复和普通用户 403；不设置则跳过这些用例。E2E 结束时会删掉自己留下的上传中任务，可以重复执行。
全文索引用例需要先执行 `scripts/migrate-v5-fulltext.sql`。

### CI

`.github/workflows/ci.yml` 在每次 push / PR 上跑五个任务：

1. `mvn test`；
2. 前端 `npm ci && npm run lint && npm run format:check && npm run test:unit && npm run build`
   （ESLint/Prettier 零容忍，Vitest 全绿，Vue 模板和 Worker 都能过编译）；
3. 起 MySQL 8.0 service，导入 `schema.sql`，构建前端后打包，用临时存储目录和一次性管理员口令
   启动 jar，安装 Playwright Chromium，跑 `web/e2e` 的浏览器用例（登录/刷新恢复/退出、
   上传→列表→搜索→下载→删除、管理员身份）；失败时上传 trace/HTML 报告；
4. 起一个 MySQL 8.0 service，导入 `schema.sql`，用临时存储目录和一次性管理员口令启动 jar，
   再跑完整的 `scripts/e2e-test.sh`（注册/登录、跨用户隔离、票据下载、限流错误码都在里面）；
5. 起 MySQL + Redis 7 service，同时启动 8081/8082 两个 JVM（同一数据库、同一共享目录、
   `cluster.enabled=true`），跑 `scripts/e2e-cluster.sh`（跨节点上传/合并/秒传/并发完成/共享限流）。

CI 里的 MySQL 口令和 `FILE_HUB_ADMIN_PASSWORD` 都是一次性容器值，不是任何真实环境的口令。

> `e2e-test.sh` 在 v1 的 25 项基础上补了分片摘要拒绝（30004）、合并时坏片自愈（30005）、
> `/status`、`/stats`、单段/多段 Range、中文全文搜索、管理员完整性扫描/修复、分页/状态过滤，
> 以及 Actuator health/metrics 的鉴权、`X-Request-Id` 回传和 OpenAPI 可读性。
> **2026-11-18 已在真实 MySQL 8.0.45 上跑通：101 项断言全部通过**（临时实例、端口 3307、
> 独立存储目录，未动本机 3306 的库）；脚本会清理自己留下的上传中任务，连续跑两遍不会互相污染。
> 另外手工验证了「并发 complete 只合并一次、两个请求都返回成功」、老分片摘要回填、
> 任务完成后迟到分片不重建目录，以及真实定时清理的条件删除和孤儿目录回收。
> 跑它需要一个已建库的环境，且脚本和服务端在同一台机器上（坏片用例要直接改磁盘分片）。

Phase 3 的运维端点在同一个实例上手工验证过（2026-11-18），当晚也补做了容器实测：

- `/actuator/health` 200；管理员看到的 `storage` 组件含 root 路径，单机模式 `redis=disabled`；
- 管理员 `/actuator/prometheus`、`/actuator/metrics` 200；不带 Token 返回 401（业务码 40001）；
- `/v3/api-docs` 200，`/swagger-ui.html` 302 到 `/swagger-ui/index.html`；
- 带 `X-Request-Id: verify-123` 的登录请求，响应头原样回传，日志行是
  `rid=verify-123 trace=<32 hex>`；
- 走完一次单分片小文件上传后，Prometheus 里出现
  `filehub_chunk_saved_total`、`filehub_upload_completed_total`、`filehub_upload_merge_duration_seconds`；
- `--spring.profiles.active=json` 时输出单行 JSON，含 `requestId` / `traceId` / `spanId`。
- **单机 compose 实跑通过**：`docker compose up --build` 构建并启动成功，mysql / redis / app
  三个容器均为 healthy；容器内 `/actuator/health` 显示 `storage.root=/data`、`redis=disabled`，
  匿名访问 prometheus 401、管理员 200，`/v3/api-docs` 正常，`X-Request-Id` 回传且日志可见
  `rid`/`trace`，`SPRING_PROFILES_ACTIVE=json` 输出含 `requestId`/`traceId`/`spanId`；
  针对容器重跑完整 E2E，**101/101 通过**。
  （E2E 里「直接改磁盘分片」的用例要求宿主机能看到存储目录，验证时临时把 `/data` 挂成
  宿主机 bind mount；仓库里的默认 compose 仍是命名卷，行为不受影响。）
- **集群 compose 也实跑过**：app1/app2 健康检查都是 200；在 8081 登录拿到的 Token 可直接
  访问 8082，分片传 8082、status 查 8082、合并点 8081、下载回 8082，SHA-256 一致；
  Redis 中出现 `filehub:` 前缀的限流键，说明集群模式确实走到了 Redis。

---

## 实测数据

环境：Windows 11 / JDK 17 (Corretto) / MySQL 8.0.45 本机 / 单实例，分片 5MB，上传并发 4。

| 文件大小 | 分片数 | 分片上传 | 服务端合并 | 合计 | 端到端吞吐 |
|---|---|---|---|---|---|
| 300 MB | 60 | 8.80 s | 2.70 s | 11.50 s | **26.1 MB/s** |
| 1000 MB | 200 | 37.05 s | 9.16 s | 46.21 s | **21.6 MB/s** |

（2026-11-18 重跑；v2 的合并阶段要逐片复算 SHA-256，比 v1 的纯拼接慢。）

- 两次下载回来的文件 SHA-256 与原文件**完全一致**——这是"分片拼对了"的唯一硬证据。
- 秒传：**服务端单次约 21–23 ms**（20 次连续请求摊掉进程启动）；客户端观感 617–810 ms，
  大头是 Windows 上两次 `curl.exe` 进程启动加一次 check + init 往返。
- 1GB 的文件走秒传，**传输量为 0**（200 个分片全部不用传）。

> 吞吐 20–26 MB/s 的量级说明：瓶颈基本在本机磁盘 IO、逐片/整文件两次 SHA-256，
> 以及 `curl.exe`/`sha256sum` 这些客户端进程上，不是服务端业务逻辑。
> 这个数字的意义是"链路是通的、没有明显瓶颈"，不是"性能上限"。
> 基准脚本每次运行会注册独立用户并清理自己产生的记录。

---

## 已知不足（别在面试里吹过头）

- **哈希在 Worker 里算，但老浏览器会回退**：整文件 SHA-256 现在跑在 Web Worker 里，
  主线程只收进度消息；不支持 Worker 的环境仍会回退到主线程，1GB 文件算哈希时会有卡顿。
  哈希结果会缓存进 localStorage，第二次选择同一文件可以直接跳过；缓存失效时服务端返回 30003，
  界面提示重新计算，不会静默传坏文件。
- **刷新页面不会自动续传**：浏览器拿不到刷新前的 `File` 对象，需要用户重新选择同名、同大小、
  同修改时间的文件，前端用指纹（name + size + lastModified）对上本地断点记录后再续传。
- **多实例依赖共享目录和 Redis**：共享目录必须支持原子 rename、mtime 一致（NFS/SMB 的语义
  要在目标环境实测）；Redis 目前是单点，没有做 Sentinel/Cluster 高可用。Redis 不可用时上传和
  登录会明确失败（`20008` / HTTP 503），这是刻意的 fail-closed，不是降级。S3/MinIO 仍不在范围内。
- **Token 存在 localStorage**：实现简单、刷新不掉线，但同源 XSS 能读到它。生产环境更稳妥的是
  HttpOnly Cookie + CSRF 防护，或配合严格 CSP；当前只做了「退出即撤销 + 7 天过期」。
- **认证范围有限**：没有找回密码、邮箱验证、OAuth、2FA、登录审计表；限流是固定窗口
  （单机 Caffeine、多实例 Redis），不是令牌桶/漏桶。
- **管理员能看旧文件**：`owner_id IS NULL` 的历史数据只对 ADMIN 可见，这是迁移期的兼容策略，
  不是团队共享空间；不做跨用户共享/协作。
- **多段 Range 有硬上限**：单次最多 8 段、总长不超过 64 MB，超出直接 416 而不是降级成
  200 整文件；每段统一按 `application/octet-stream` 输出，没有做按段内容类型协商。
- **全文索引依赖 MySQL 8 ngram**：1 个字的关键词回退 `LIKE %kw%`（会全表扫），2 字以上才走
  `MATCH ... AGAINST`；英文/数字短词受 `ngram_token_size`（默认 2）影响，改这个参数要重启
  并重建索引；大表首次加索引会锁表，应在低峰执行迁移脚本。
- **端到端脚本依赖本机环境**：坏片自愈用例要直接改服务端磁盘上的分片文件，所以脚本必须和
  服务端同机跑（CI 里就是同 runner）；脚本现在会清理自己留下的「上传中」任务，但成功用例的
  成品文件仍会留在临时目录里，由 CI 的 runner 自行回收。基准数据已按 v2 重跑，但仍是单机、
  本机磁盘、客户端和服务器在同一个 Windows 会话里的结果，不代表跨网络或生产硬件的吞吐。
- **浏览器 E2E 只覆盖单一内核**：Playwright 目前只配了一个项目（本机 `msedge`，CI 用
  Chromium），Safari/WebKit 和 Firefox 没有跑；上传用例的 Worker 哈希、`AbortSignal.any`
  等分支在其他内核上的差异没有被自动验证。管理员完整性面板的用例已取消 `fixme` 并在 Edge
  上跑通（入口可见、扫描摘要渲染），但「删物理文件 → 扫描命中 → 修复」的完整链路只在
  curl E2E 里覆盖。
- **清理任务与暂停冲突**：一个任务暂停超过 `upload-expire-minutes` 后被回收，用户再点继续时
  `init` 会新建任务，需要从头传（数据是安全的，只是省不掉重传）。现在的条件删除 + 续传心跳
  保证"刚回来续传却被误删"不会发生，但已经回收的分片找不回来。
- **观测性只到“本进程”**：指标存在内存里，重启归零；没有接 Prometheus Server/Grafana，
  tracing 只做本地 MDC 关联（没配 Zipkin exporter），采样率固定 100%。生产要按流量调低采样，
  并把 `/actuator/prometheus` 交给内网采集器而不是浏览器。
- **接口文档在演示环境是公开的**：`/swagger-ui.html` 和 `/v3/api-docs` 当前 permitAll，
  方便面试演示和本地调试；生产应改成仅管理员/内网（`SecurityConfig` 一行）或关掉 swagger。
- **Docker 镜像没有进 CI**：镜像构建与单机/集群 compose 已在本机 Docker Desktop 实测通过
  （2026-11-18），但 CI 没有加 `docker build` / `docker compose up` 任务，也没有推镜像仓库；
  换机器或换架构（如 arm64）仍需自行验证。

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
11. 为什么用不透明 Token 而不是 JWT？
   → JWT 的撤销要额外维护黑名单；这个服务本来就有 MySQL，直接存 SHA-256 会话哈希，
   撤销就是删一行，过期就是查 `expires_at`，语义最直接。代价是每次请求多一次索引查询，
   对这个量级可以接受；真要扩展再上 Redis 会话缓存。
12. 两个用户上传同一个文件，秒传会不会串号？
   → 不会。`check` 只在自己可见的记录里找；`init` 命中物理去重时，给当前用户新建一条
   带自己 `owner_id` 的 DONE 记录，返回新的 fileId。物理文件共享，逻辑记录隔离。
13. 浏览器下载怎么带鉴权？
   → 先换 5 分钟、绑定 file + user 的票据，再拼 `?ticket=`。票据只存哈希、可过期；
   `Authorization` 头留给脚本和 curl，两条路径在下载接口汇合后统一做 owner 校验。
14. 线上有人说"上传卡住了"，你怎么定位？
   → 先拿响应头的 `X-Request-Id` 在日志里找这一个请求（JSON 日志还有 traceId/spanId，
   能连起同一请求里的所有事件）；再看 `/actuator/health` 的 `storage`（root 不可写或
   集群模式 Redis 断了都会直接 DOWN）；然后用 Prometheus 看
   `filehub_upload_merge_failed{code}` 区分缺片/摘要不符/坏片，看
   `filehub_upload_merge_duration` 判断是不是合并变慢，看 `filehub_rate_limit_rejected{scope}`
   排除被限流。指标、日志、health 三条线对应"哪里慢/哪个请求/哪个依赖坏了"。
