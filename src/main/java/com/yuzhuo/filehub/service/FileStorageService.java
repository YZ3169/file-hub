package com.yuzhuo.filehub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.common.LockRegistry;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.dto.CheckResponse;
import com.yuzhuo.filehub.dto.FileVO;
import com.yuzhuo.filehub.dto.InitUploadRequest;
import com.yuzhuo.filehub.dto.InitUploadResponse;
import com.yuzhuo.filehub.dto.PageResponse;
import com.yuzhuo.filehub.dto.StatsResponse;
import com.yuzhuo.filehub.dto.UploadStatusResponse;
import com.yuzhuo.filehub.entity.FileChunk;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.mapper.FileChunkMapper;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.storage.LocalDiskStorage;
import com.yuzhuo.filehub.storage.StorageBackend;
import com.yuzhuo.filehub.util.HashUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * 分片上传 / 秒传 / 断点续传的全部业务逻辑。
 *
 * <p>贯穿整个类的不变量（记住这一条就能讲清所有分支）：
 * <b>物理存储按内容寻址 —— 同一个 (hash, size) 在磁盘上只有一份成品</b>。
 * 秒传是在上传前命中它，合并时的去重是在上传后命中它，两者是同一个不变量在不同时机的应用。</p>
 */
@Slf4j
@Service
public class FileStorageService {

    /**
     * 孤儿成品回收与「复用已有物理文件」之间的互斥锁。
     * 锁顺序固定：file/content 锁 → gc 锁；GC 只拿 gc 锁，不反向拿内容锁。
     */
    private static final String GC_LOCK = "gc:physical";

    private final FileInfoMapper fileInfoMapper;
    private final FileChunkMapper fileChunkMapper;
    private final LocalDiskStorage chunkStorage;
    private final StorageBackend objectStorage;
    private final StorageProperties props;
    private final LockRegistry locks;
    private final MeterRegistry meters;
    private QuotaService quotaService;

    @Autowired(required = false)
    public void setQuotaService(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @Autowired
    public FileStorageService(FileInfoMapper fileInfoMapper,
                              FileChunkMapper fileChunkMapper,
                              LocalDiskStorage chunkStorage,
                              StorageBackend objectStorage,
                              StorageProperties props,
                              LockRegistry locks,
                              MeterRegistry meters) {
        this.fileInfoMapper = fileInfoMapper;
        this.fileChunkMapper = fileChunkMapper;
        this.chunkStorage = chunkStorage;
        this.objectStorage = objectStorage;
        this.props = props;
        this.locks = locks;
        this.meters = meters;
    }

    /** 兼容单本地存储的构造器，方便现有单元测试直接使用 */
    public FileStorageService(FileInfoMapper fileInfoMapper,
                              FileChunkMapper fileChunkMapper,
                              LocalDiskStorage storage,
                              StorageProperties props,
                              LockRegistry locks,
                              MeterRegistry meters) {
        this(fileInfoMapper, fileChunkMapper, storage, storage, props, locks, meters);
    }

    // ==================================================================
    // 1. 秒传预检
    // ==================================================================

    /**
     * 上传前先问一句「这个文件你库里有没有」。命中就一个字节都不用传。
     *
     * <p>命中还必须以磁盘为准再确认一次：如果物理文件被外部删掉或大小不符，
     * 这条「已完成」记录就是打不开的，按未命中处理，让客户端走正常上传。</p>
     */
    public CheckResponse check(String hash, long size, UserPrincipal user) {
        FileInfo hit = findUsableDoneForOwner(hash, size, user);
        return new CheckResponse(hit != null, hit == null ? null : hit.getFileId());
    }

    // ==================================================================
    // 2. 初始化上传
    // ==================================================================

    /**
     * 初始化。三种走向：
     * <ol>
     *   <li><b>秒传命中</b>：直接建一条已完成记录，共享已有物理文件；</li>
     *   <li><b>断点续传</b>：同文件名、相对路径和 hash+size 已有上传中的任务，复用并回传已传分片；</li>
     *   <li>全新上传：建任务。</li>
     * </ol>
     *
     * @param user 当前登录用户；秒传/断点续传都只在有权限的记录里匹配
     */
    public InitUploadResponse init(InitUploadRequest req, UserPrincipal user) {
        validateRelativePath(req);
        long configuredChunkSize = props.getChunkSize();
        if (configuredChunkSize <= 0 || configuredChunkSize > Integer.MAX_VALUE) {
            throw new BizException(50001, "服务端分片大小配置非法：" + configuredChunkSize);
        }
        int chunkSize = (int) configuredChunkSize;
        int chunkTotal = calcChunkTotal(req.fileSize(), chunkSize);
        String fileHash = req.fileHash().toLowerCase();

        // --- 情况 1：秒传 ---
        // 同一份内容的「新增引用」和「删除最后一条引用」必须互斥：否则删除方刚数完
        // 引用为 0，秒传方又插进来一条指向同一物理文件的记录，就会得到一条打不开的完成记录。
        InitUploadResponse instant = locks.write(contentKey(fileHash, req.fileSize()), () ->
                // 再套一个读锁挡住「孤儿成品回收」：否则回收任务可能刚数完引用为 0，
                // 本请求就插入了新引用，随后回收把这份仍被引用的物理文件删掉。
                locks.read(GC_LOCK, () -> {
                    FileInfo done = findUsableDone(fileHash, req.fileSize());
                    if (done == null) {
                        return null;
                    }
                    checkQuota(user, req.fileSize());
                    String newFileId = UUID.randomUUID().toString();
                    FileInfo row = newRow(newFileId, req, chunkSize, chunkTotal, user);
                    row.setStatus(FileInfo.STATUS_DONE);
                    // 新记录指向同一份物理文件：磁盘上不再存第二份
                    row.setStoragePath(done.getStoragePath());
                    fileInfoMapper.insert(row);
                    log.info("秒传命中：{} -> 复用 {}", req.fileName(), done.getStoragePath());
                    return new InitUploadResponse(newFileId, true, chunkSize, chunkTotal, req.fileSize(), List.of());
                }));
        if (instant != null) {
            meters.counter("filehub.upload.instant_hits").increment();
            meters.counter("filehub.upload.instant_bytes").increment(req.fileSize());
            return instant;
        }

        // --- 情况 2：自己的同一文件上传中任务（刷新页面 / 换标签页） ---
        // 必须带 owner_id：否则用户 B 可以把用户 A 未完成任务的 fileId 拿走继续传。
        // MySQL 默认排序规则忽略大小写/部分 Unicode 差异。SQL 先缩小范围，再用 Java
        // 精确比较路径和文件名，防止 Folder/a.txt 与 folder/a.txt 共用一个未完成任务。
        FileInfo ongoing = fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getOwnerId, user.id())
                .eq(FileInfo::getFileHash, fileHash)
                .eq(FileInfo::getFileSize, req.fileSize())
                .eq(FileInfo::getFileName, req.fileName())
                .eq(FileInfo::getRelativePath, req.relativePath())
                .eq(FileInfo::getStatus, FileInfo.STATUS_UPLOADING)
                .orderByDesc(FileInfo::getId)).stream()
                .filter(f -> req.fileName().equals(f.getFileName())
                        && req.relativePath().equals(f.getRelativePath()))
                .findFirst().orElse(null);
        if (ongoing != null) {
            // 用户主动回来续传，等于一次心跳：刷新 update_time，避免刚 init 完就被清理任务回收。
            // 如果任务恰好已被清理或合并（更新 0 行），就按全新任务处理。
            if (fileInfoMapper.touchUploading(ongoing.getFileId()) > 0) {
                List<Integer> uploaded = uploadedIndexes(ongoing.getFileId());
                log.info("复用上传中任务 {}，已传 {}/{} 片", ongoing.getFileId(), uploaded.size(), ongoing.getChunkTotal());
                return new InitUploadResponse(ongoing.getFileId(), false, ongoing.getChunkSize(),
                        ongoing.getChunkTotal(), ongoing.getFileSize(), uploaded);
            }
            log.info("上传中任务 {} 已结束，改为新建任务", ongoing.getFileId());
        }

        // --- 情况 3：全新任务 ---
        checkQuota(user, req.fileSize());
        String fileId = UUID.randomUUID().toString();
        FileInfo row = newRow(fileId, req, chunkSize, chunkTotal, user);
        fileInfoMapper.insert(row);
        log.info("新建上传任务：{}（{} 字节，{} 片）", fileId, req.fileSize(), chunkTotal);
        return new InitUploadResponse(fileId, false, chunkSize, chunkTotal, req.fileSize(), List.of());
    }

    private FileInfo newRow(String fileId, InitUploadRequest req, int chunkSize, int chunkTotal,
                            UserPrincipal user) {
        FileInfo row = new FileInfo();
        row.setFileId(fileId);
        row.setFileName(req.fileName());
        row.setRelativePath(req.relativePath());
        row.setFileSize(req.fileSize());
        row.setFileHash(req.fileHash().toLowerCase());
        row.setChunkSize(chunkSize);
        row.setChunkTotal(chunkTotal);
        row.setStatus(FileInfo.STATUS_UPLOADING);
        row.setOwnerId(user.id());
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        return row;
    }

    private static void validateRelativePath(InitUploadRequest req) {
        String path = req.relativePath();
        if (path.isEmpty()) {
            return;
        }
        if (path.length() > 4096 || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.matches("^[a-zA-Z]:.*")
                || path.codePoints().anyMatch(Character::isISOControl)) {
            throw new BizException(10001, "relativePath 必须是合法的相对路径，长度不能超过 4096");
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new BizException(10001, "relativePath 不能包含空目录、. 或 ..");
            }
        }
        if (!parts[parts.length - 1].equals(req.fileName())) {
            throw new BizException(10001, "relativePath 的末尾必须与 fileName 一致");
        }
    }

    /**
     * 分片总数由服务端推导，不接受前端传入——前端算错一次就会合并出坏文件。
     *
     * <p>用 {@code (fileSize - 1) / chunkSize + 1} 而不是 {@code (fileSize + chunkSize - 1) / chunkSize}：
     * 后者在 fileSize 接近 Long.MAX_VALUE 时会先溢出。结果超过 int 时明确报错，
     * 不静默截断——截断后的分片数会让合并逻辑读错片。</p>
     */
    static int calcChunkTotal(long fileSize, int chunkSize) {
        if (fileSize < 0) {
            throw new BizException(10007, "文件大小不能为负数：" + fileSize);
        }
        if (chunkSize <= 0) {
            throw new BizException(50001, "分片大小必须大于 0：" + chunkSize);
        }
        if (fileSize == 0) {
            return 0;
        }
        long total = (fileSize - 1) / chunkSize + 1;
        if (total > Integer.MAX_VALUE) {
            throw new BizException(10007, "文件过大：分片数 " + total + " 超过系统上限 " + Integer.MAX_VALUE);
        }
        return (int) total;
    }

    // ==================================================================
    // 3. 上传单个分片
    // ==================================================================

    /**
     * 落盘一个分片，并做分片级完整性校验。
     *
     * <p>客户端把这一片的 SHA-256 一起带上来，服务端边落盘边复算。这样网络传输中出现
     * 位翻转时，错误在这里就被拦住（返回 30004，可重试），而不是等到几十片拼完、
     * 整文件哈希对不上才发现——那时用户只能整文件重传。</p>
     *
     * <p>并发上传同一分片时靠 file_chunk 的唯一索引 + upsert 兜底：
     * 后到的会覆盖元数据，但磁盘上的分片仍是原子改名，不会出现半截文件。</p>
     */
    public void saveChunk(String fileId, int index, InputStream in, String expectedChunkHash,
                          UserPrincipal user) {
        LocalDiskStorage.requireSafeId(fileId);
        // 读锁：同一任务的多个分片可以并发落盘，但合并/删除会等这些写入完成再拿写锁
        locks.readVoid(fileId, () -> doSaveChunk(fileId, index, in, expectedChunkHash, user));
    }

    private void doSaveChunk(String fileId, int index, InputStream in, String expectedChunkHash,
                             UserPrincipal user) {
        // 权限检查必须在锁内、写入之前：非 owner 连分片序号都不能试探
        FileInfo info = requireInfo(fileId, user);

        if (info.getStatus() == FileInfo.STATUS_DONE) {
            // 已完成的任务不该再收分片，但也不值得报错——客户端可能在收到响应前重试过
            log.warn("文件 {} 已完成，忽略分片 {}", fileId, index);
            return;
        }
        if (index < 0 || index >= info.getChunkTotal()) {
            throw new BizException(10004, "分片序号越界：" + index + "，应在 [0, " + info.getChunkTotal() + ") 内");
        }

        long expectedSize = expectedChunkSize(info, index);
        LocalDiskStorage.ChunkWriteResult result;
        try {
            result = chunkStorage.saveChunk(fileId, index, in, expectedChunkHash, expectedSize);
        } catch (IOException e) {
            throw new BizException(20003, "分片落盘失败：" + e.getMessage());
        }

        if (!result.accepted()) {
            // 注意：这种情况没有覆盖旧分片。客户端重传的坏字节不会把之前正确的那片弄丢
            throw new BizException(30004,
                    "分片 " + index + " 校验失败：收到 " + result.size() + " 字节 / SHA-256 " + result.sha256()
                            + "，与声明的长度或摘要不一致，请重传该分片");
        }

        fileChunkMapper.upsertChunk(fileId, index, result.size(), result.sha256());
        // 条件更新：任务已被清理（行没了）或已被合并（status=1）时返回 0。
        // 这一步必须在 upsert 之后做，否则刚写的分片元数据会变成无主数据。
        int touched = fileInfoMapper.touchUploading(fileId);
        if (touched == 0) {
            chunkStorage.deleteChunk(fileId, index);
            fileChunkMapper.deleteChunk(fileId, index);
            FileInfo after = findInfo(fileId);
            if (after != null && after.getStatus() == FileInfo.STATUS_DONE) {
                log.info("文件 {} 已完成，迟到的分片 {} 已丢弃", fileId, index);
                return;
            }
            throw new BizException(10005, "上传任务已结束或已过期，请重新初始化");
        }
        meters.counter("filehub.chunk.saved").increment();
        meters.counter("filehub.chunk.bytes").increment(result.size());
    }

    /** 第 index 片应有的字节数：除最后一片外都等于 chunkSize */
    static long expectedChunkSize(FileInfo info, int index) {
        long start = (long) index * info.getChunkSize();
        return Math.min(info.getChunkSize(), info.getFileSize() - start);
    }

    // ==================================================================
    // 4. 合并
    // ==================================================================

    /**
     * 合并全部分片成成品。
     *
     * <p>关键点：</p>
     * <ul>
     *   <li><b>流式</b>：一片一片 transferTo，内存里只过一个 8KB 缓冲区，多大文件都不会 OOM；</li>
     *   <li><b>边写边算摘要</b>：DigestOutputStream 让落盘和 SHA-256 一次过，不用读完再算一遍；</li>
     *   <li><b>先校验后落地</b>：hash 对不上就把临时文件删掉报错，绝不产生"看起来上传成功但内容是坏的"文件；</li>
     *   <li><b>完成动作幂等</b>：markDone 带 status=0 条件，两个请求同时点完成只有一个能生效。</li>
     * </ul>
     */
    public FileVO complete(String fileId, UserPrincipal user) {
        LocalDiskStorage.requireSafeId(fileId);
        // 写锁覆盖整个「检查 -> 合并 -> markDone -> 删分片」过程。
        // 两个并发 complete 会被串行化，后到的进来时能看到完成态并直接返回。
        // 注意这里不再用 @Transactional：方法里的每次 mapper 调用各自提交，
        // 锁释放时数据库状态已经对其他线程可见，不会出现「锁放了但事务还没提交」的窗口。
        long start = System.nanoTime();
        try {
            FileVO done = locks.write(fileId, () -> doComplete(fileId, user));
            meters.counter("filehub.upload.completed").increment();
            meters.counter("filehub.upload.merge_bytes").increment(done.fileSize());
            return done;
        } catch (BizException e) {
            // 缺片、坏片、校验失败都算合并失败；按错误码拆开，便于在面板上区分
            meters.counter("filehub.upload.merge_failed", "code", String.valueOf(e.getCode())).increment();
            throw e;
        } finally {
            meters.timer("filehub.upload.merge_duration").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private FileVO doComplete(String fileId, UserPrincipal user) {
        FileInfo info = requireInfo(fileId, user);

        if (info.getStatus() == FileInfo.STATUS_DONE) {
            // 完成态也不能盲信数据库：物理文件被外部删掉时，直接返回成功等于骗客户端
            if (!isStorageIntact(info)) {
                throw new BizException(20007, "文件已完成但物理内容缺失或大小不符，无法合并/下载");
            }
            log.info("文件 {} 已经是完成态，直接返回", fileId);
            return FileVO.from(info);
        }

        // 以磁盘为准判断齐没齐：数据库说传过、但文件丢了（比如被清理过），
        // 这里能如实发现并要求重传，而不是拼出一个缺片的坏文件
        List<Integer> onDisk = chunkStorage.listChunksOnDisk(fileId);
        Set<Integer> present = new HashSet<>(onDisk);
        List<Integer> missing = IntStream.range(0, info.getChunkTotal())
                .filter(i -> !present.contains(i))
                .boxed()
                .toList();
        if (!missing.isEmpty()) {
            throw new BizException(30001, "还有 " + missing.size() + " 个分片没传完，缺失序号：" + abbreviate(missing));
        }

        // 逐片摘要：数据库里记着每片上传时服务端复算过的 SHA-256
        Map<Integer, String> expectedChunkHashes = new HashMap<>();
        for (FileChunk c : fileChunkMapper.listChunkMeta(fileId)) {
            if (c.getChunkHash() != null && !c.getChunkHash().isBlank()) {
                expectedChunkHashes.put(c.getChunkIndex(), c.getChunkHash());
            }
        }

        Path merged = null;
        String actualHash;
        long actualSize = 0;
        Set<Integer> corrupt = new HashSet<>();
        // v2 之前创建的任务没有 chunk_hash。整文件校验通过后把实测摘要补回去，
        // 下次再合并遇到坏片时就能精确到片自愈。
        Map<Integer, String> hashesToBackfill = new HashMap<>();
        try {
            merged = chunkStorage.createMergeTmpFile(fileId);
            var digest = HashUtil.sha256();
            try (OutputStream fileOut = Files.newOutputStream(merged);
                 BufferedOutputStream buffered = new BufferedOutputStream(fileOut, 1 << 16);
                 DigestOutputStream digestOut = new DigestOutputStream(buffered, digest)) {
                for (int i = 0; i < info.getChunkTotal(); i++) {
                    // 每片单独算一次摘要，同时把字节写进整文件摘要流：
                    // 磁盘只读一遍，却能同时回答"这一片对不对"和"整个文件对不对"
                    MessageDigest chunkDigest = HashUtil.sha256();
                    long chunkBytes;
                    try (InputStream chunkIn = Files.newInputStream(chunkStorage.chunkFile(fileId, i));
                         DigestInputStream digestIn = new DigestInputStream(chunkIn, chunkDigest)) {
                        chunkBytes = digestIn.transferTo(digestOut);
                    }
                    actualSize += chunkBytes;

                    if (chunkBytes != expectedChunkSize(info, i)) {
                        corrupt.add(i);
                    }
                    String actualChunkHash = HexFormat.of().formatHex(chunkDigest.digest());
                    String expectedChunkHash = expectedChunkHashes.get(i);
                    if (expectedChunkHash != null) {
                        if (!expectedChunkHash.equalsIgnoreCase(actualChunkHash)) {
                            corrupt.add(i);
                        }
                    } else {
                        hashesToBackfill.put(i, actualChunkHash);
                    }
                }
            }
            actualHash = HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            deleteQuietly(merged);
            throw new BizException(20004, "合并分片失败：" + e.getMessage());
        }

        // --- 分片级校验失败：只删坏片，让客户端补传这几片，而不是整个文件重来 ---
        if (!corrupt.isEmpty()) {
            deleteQuietly(merged);
            List<Integer> bad = corrupt.stream().sorted().toList();
            for (int i : bad) {
                chunkStorage.deleteChunk(fileId, i);
                fileChunkMapper.deleteChunk(fileId, i);
            }
            throw new BizException(30005, "逐片校验发现 " + bad.size()
                    + " 个损坏分片，已删除，请只补传这些分片：" + abbreviate(bad));
        }

        // --- 完整性校验：两道，都不能省 ---
        if (actualSize != info.getFileSize()) {
            deleteQuietly(merged);
            throw new BizException(30002, "合并后大小不符：期望 " + info.getFileSize() + "，实际 " + actualSize);
        }
        if (!actualHash.equalsIgnoreCase(info.getFileHash())) {
            deleteQuietly(merged);
            throw new BizException(30003, "内容校验失败：声明的 SHA-256 与合并结果不一致，文件可能已损坏");
        }

        // 整文件校验通过，说明所有分片拼起来就是声明的内容，这时才敢回填老任务的逐片摘要。
        // 写文件行与删分片都在同一把文件写锁内，不会和 saveChunk/清理交错。
        for (Map.Entry<Integer, String> e : hashesToBackfill.entrySet()) {
            fileChunkMapper.upsertChunk(fileId, e.getKey(),
                    expectedChunkSize(info, e.getKey()), e.getValue());
        }
        if (!hashesToBackfill.isEmpty()) {
            log.info("回填了 {} 个历史分片的 SHA-256：{}", hashesToBackfill.size(), fileId);
        }

        // --- 上传后去重：并发传同一个文件时，后完成的复用先完成者的物理文件 ---
        // 与秒传插入、删除最后引用共用同一把内容锁：
        // 如果这里读到 existing 后它刚好被删除，物理文件就会在 markDone 之前消失。
        final Path mergedFile = merged;
        String storagePath = locks.write(contentKey(info.getFileHash(), info.getFileSize()), () ->
                // 锁顺序固定为 file/content -> gc：先拿内容锁再拿 GC 读锁，GC 只拿 gc 锁，
                // 不会反向等待内容锁，因此不会死锁。
                locks.read(GC_LOCK, () -> {
                    FileInfo existing = findUsableDone(info.getFileHash(), info.getFileSize());
                    String path;
                    if (existing != null) {
                        deleteQuietly(mergedFile);
                        path = existing.getStoragePath();
                        log.info("合并后去重：{} 复用已有物理文件 {}", fileId, path);
                    } else {
                        path = chunkStorage.buildRelativePath(fileId);
                        try {
                            objectStorage.put(path, mergedFile);
                        } catch (IOException e) {
                            deleteQuietly(mergedFile);
                            throw new BizException(20005, "写入存储失败：" + e.getMessage());
                        }
                    }
                    int updated = fileInfoMapper.markDone(fileId, path);
                    if (updated == 0) {
                        // 本 JVM 内不会走到这里（文件写锁串行化 complete）；跨实例时另一请求先改了。
                        // 内容寻址保证两边算出的路径相同，无害。
                        log.info("文件 {} 已被并发请求置为完成态", fileId);
                    }
                    return path;
                }));
        // 合并成功后分片文件与分片元数据都没有价值了。先删库再删盘：
        // 如果进程在两者之间被杀，留下的是「有目录无元数据」，孤儿目录回收还能扫到；
        // 反过来先删盘会留下一堆永远不会被扫到的 file_chunk 行。
        fileChunkMapper.deleteByFileId(fileId);
        chunkStorage.deleteChunks(fileId);

        FileInfo after = requireInfo(fileId, user);
        log.info("文件 {} 合并完成：{} 字节，{} 片，hash={}", fileId, actualSize, info.getChunkTotal(), actualHash);
        return FileVO.from(after);
    }

    // ==================================================================
    // 5. 查询 / 下载 / 删除
    // ==================================================================

    /**
     * 文件列表（分页 + 关键字 + 状态过滤 + 白名单排序）。
     *
     * <p>之前是硬编码 {@code LIMIT 200}：超过 200 条的记录永远看不到，也没法搜索。
     * 现在分页参数由服务端夹紧（page ≥ 1，1 ≤ size ≤ 100），排序只接受白名单，
     * 避免把前端传来的列名直接拼进 SQL。</p>
     */
    public PageResponse<FileVO> list(int page, int size, String keyword, Integer status, String sort,
                                     UserPrincipal user, String scope) {
        return list(page, size, keyword, status, null, sort, user, scope);
    }

    public PageResponse<FileVO> list(int page, int size, String keyword, Integer status,
                                     String category, String sort, UserPrincipal user, String scope) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));

        if (status != null && status != FileInfo.STATUS_UPLOADING
                && status != FileInfo.STATUS_DONE && status != FileInfo.STATUS_TRASH) {
            throw new BizException(10008, "status 只能是 0（上传中）、1（已完成）或 2（回收站）");
        }
        Set<String> categoryExts = com.yuzhuo.filehub.util.FileMediaTypeResolver.extensionsForCategory(category);
        String catKey = category == null ? "" : category.trim().toLowerCase(java.util.Locale.ROOT);

        LambdaQueryWrapper<FileInfo> qw = new LambdaQueryWrapper<>();
        applyOwnerScope(qw, user, scope);
        if (status != null) {
            qw.eq(FileInfo::getStatus, status);
        } else {
            qw.ne(FileInfo::getStatus, FileInfo.STATUS_TRASH);
        }
        if (!categoryExts.isEmpty()) {
            qw.and(w -> {
                boolean first = true;
                for (String ext : categoryExts) {
                    if (!first) {
                        w.or();
                    }
                    w.apply("LOWER(file_name) LIKE {0}", "%." + ext);
                    first = false;
                }
                if ("document".equals(catKey)) {
                    w.or().apply("LOWER(file_name) IN ('readme', 'license')");
                } else if ("code".equals(catKey)) {
                    w.or().apply("LOWER(file_name) IN ('dockerfile', 'makefile')");
                }
            });
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            if (kw.matches("^[0-9a-fA-F]{64}$")) {
                // 完整的 SHA-256：精确匹配才能走 file_hash 索引，也避免把 64 位串当成分词拆碎
                qw.eq(FileInfo::getFileHash, kw.toLowerCase(java.util.Locale.ROOT));
            } else if (props.getSearch().isFulltextEnabled() && kw.codePointCount(0, kw.length()) >= 2) {
                // 中文文件名用 ngram 全文索引：LIKE '%关键词%' 在几万行之后必然全表扫描。
                // file_id / file_hash 仍保留 LIKE，方便按短标识前缀找文件。
                qw.and(w -> w.apply("MATCH(file_name) AGAINST({0} IN NATURAL LANGUAGE MODE)", kw)
                        .or().like(FileInfo::getFileId, kw)
                        .or().like(FileInfo::getFileHash, kw));
            } else {
                // 单个字/字符：ngram 最小分词长度是 2，全文索引查不到，回退 LIKE。
                // 桌面版 H2 没有 FULLTEXT，全文开关关闭后也走这里。
                qw.and(w -> w.like(FileInfo::getFileName, kw)
                        .or().like(FileInfo::getFileHash, kw)
                        .or().like(FileInfo::getFileId, kw));
            }
        }
        switch (sort == null ? "" : sort) {
            case "time_asc" -> qw.orderByAsc(FileInfo::getId);
            case "size_desc" -> qw.orderByDesc(FileInfo::getFileSize).orderByDesc(FileInfo::getId);
            case "size_asc" -> qw.orderByAsc(FileInfo::getFileSize).orderByDesc(FileInfo::getId);
            case "name_asc" -> qw.orderByAsc(FileInfo::getFileName).orderByDesc(FileInfo::getId);
            // 默认最新优先；未知排序值回退到默认，不报错
            default -> qw.orderByDesc(FileInfo::getId);
        }

        Page<FileInfo> result = fileInfoMapper.selectPage(new Page<>(safePage, safeSize), qw);
        return new PageResponse<>(
                result.getRecords().stream().map(FileVO::from).toList(),
                result.getTotal(), safePage, safeSize);
    }

    /**
     * 取文件并校验权限。非 owner（且非管理员）统一返回「文件不存在」，
     * 不暴露 fileId 是否真实存在，避免拿来枚举别人的文件。
     */
    public FileInfo requireInfo(String fileId, UserPrincipal user) {
        FileInfo info = findInfo(fileId);
        if (info == null || !canAccess(info, user)) {
            throw new BizException(10005, "文件不存在：" + fileId);
        }
        return info;
    }

    /**
     * 供匿名分享校验使用：只看文件本身，不校验调用者权限。
     * 文件不存在、未完成，或磁盘内容缺失/大小不符，都返回 null。
     */
    public FileInfo findDoneForShare(String fileId) {
        FileInfo info = findInfo(fileId);
        if (info == null || info.getStatus() != FileInfo.STATUS_DONE || !isStorageIntact(info)) {
            return null;
        }
        return info;
    }

    private boolean canAccess(FileInfo info, UserPrincipal user) {
        if (user == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        // owner_id = NULL 是升级前的历史数据，普通用户不可见
        return info.getOwnerId() != null && info.getOwnerId().equals(user.id());
    }

    /**
     * 普通用户只能看到自己的文件；管理员默认看到自己的 + 历史数据（owner_id IS NULL），
     * 显式传 scope=all 才看全部。普通用户传 scope=all 无效。
     */
    private void applyOwnerScope(LambdaQueryWrapper<FileInfo> qw, UserPrincipal user, String scope) {
        if (user.isAdmin() && "all".equalsIgnoreCase(scope)) {
            return;
        }
        if (user.isAdmin()) {
            qw.and(w -> w.eq(FileInfo::getOwnerId, user.id())
                    .or().isNull(FileInfo::getOwnerId));
        } else {
            qw.eq(FileInfo::getOwnerId, user.id());
        }
    }

    private FileInfo findInfo(String fileId) {
        return fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getFileId, fileId));
    }

    /**
     * 找一条「数据库和磁盘都算数」的已完成记录。
     *
     * <p>只看 status=1 不够：物理文件可能被外部删掉、被误清理，或者文件大小对不上。
     * 这时如果仍然返回秒传命中，客户端拿到的就是一个永远下载失败的文件 id。
     * 同一份内容可能有历史遗留的多条记录，逐条检查，找到第一条真的能用的。</p>
     */
    private FileInfo findUsableDone(String hash, long size) {
        List<FileInfo> doneList = fileInfoMapper.findDoneListByHashAndSize(hash.toLowerCase(), size);
        for (FileInfo done : doneList) {
            if (isStorageIntact(done)) {
                return done;
            }
            log.warn("已完成记录 {} 的物理文件缺失或大小不符，跳过：{}",
                    done.getFileId(), done.getStoragePath());
        }
        return null;
    }

    /** 秒传预检的可见范围版本：只返回当前用户（管理员含历史数据）名下的可用记录 */
    private FileInfo findUsableDoneForOwner(String hash, long size, UserPrincipal user) {
        List<FileInfo> doneList = fileInfoMapper.findDoneListByOwnerAndHashAndSize(
                hash.toLowerCase(), size, user.id(), user.isAdmin());
        for (FileInfo done : doneList) {
            if (isStorageIntact(done)) {
                return done;
            }
            log.warn("已完成记录 {} 的物理文件缺失或大小不符，跳过：{}",
                    done.getFileId(), done.getStoragePath());
        }
        return null;
    }

    /** 数据库说完成、磁盘上也确实有一份大小一致的文件，三者同时成立才算完好 */
    public boolean isStorageIntact(FileInfo info) {
        String path = info.getStoragePath();
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            return objectStorage.exists(path) && objectStorage.size(path) == info.getFileSize();
        } catch (RuntimeException e) {
            log.warn("检查物理文件失败：{}", path, e);
            return false;
        }
    }

    /** 内容锁的 key：同一 (hash, size) 在磁盘上只有一份成品，所有引用都靠它互斥 */
    private static String contentKey(String hash, long size) {
        return "content:" + hash.toLowerCase() + ":" + size;
    }

    /** 已落盘的分片序号（以磁盘为准） */
    public List<Integer> uploadedIndexes(String fileId) {
        return chunkStorage.listChunksOnDisk(fileId);
    }

    /**
     * 上传任务状态。以磁盘为准回答"现在到底有哪些片"——
     * 暂停后继续、页面刷新重连、合并发现坏片后补传，都靠这个接口重新对齐。
     */
    public UploadStatusResponse status(String fileId, UserPrincipal user) {
        LocalDiskStorage.requireSafeId(fileId);
        FileInfo info = requireInfo(fileId, user);
        return new UploadStatusResponse(
                info.getFileId(),
                info.getFileName(),
                info.getFileSize(),
                info.getStatus(),
                info.getStatus() == FileInfo.STATUS_DONE,
                info.getChunkSize(),
                info.getChunkTotal(),
                chunkStorage.listChunksOnDisk(fileId));
    }

    public StatsResponse stats(UserPrincipal user, String scope) {
        boolean all = user.isAdmin() && "all".equalsIgnoreCase(scope);
        boolean includeLegacy = user.isAdmin();
        long done;
        long uploading;
        long logical;
        long physical;
        if (all) {
            done = fileInfoMapper.countDone();
            uploading = fileInfoMapper.countUploading();
            logical = fileInfoMapper.sumLogicalBytes();
            physical = fileInfoMapper.sumPhysicalBytes();
        } else {
            done = fileInfoMapper.countDoneByOwner(user.id(), includeLegacy);
            uploading = fileInfoMapper.countUploadingByOwner(user.id(), includeLegacy);
            logical = fileInfoMapper.sumLogicalBytesByOwner(user.id(), includeLegacy);
            physical = fileInfoMapper.sumPhysicalBytesByOwner(user.id(), includeLegacy);
        }
        long quotaBytes = quotaService == null ? -1L : quotaService.quotaBytesFor(user.id());
        long quotaUsedBytes = quotaService == null ? logical : quotaService.usedBytesFor(user.id());
        return new StatsResponse(done, uploading, logical, physical, Math.max(0, logical - physical),
                quotaBytes, quotaUsedBytes);
    }

    /**
     * 回收「创建后再无动静」的上传任务。
     *
     * <p>判据是 update_time 而不是 create_time：用户可能花几小时传一个 20GB 的文件，
     * 只要每片都在更新 update_time 就不该被回收。超过 upload-expire-minutes 一片都没落盘的，
     * 才认为任务已废弃。</p>
     */
    @Scheduled(fixedDelayString = "${file-hub.cleanup-interval-ms:600000}",
            initialDelayString = "${file-hub.cleanup-initial-delay-ms:60000}")
    public void cleanupExpiredUploads() {
        // 多实例时只让抢到锁的一个实例扫描：两个实例同时扫同一批过期任务没有收益，
        // 还会成倍增加对共享盘的 IO。本地模式 tryWrite 永远成功，行为不变。
        long start = System.nanoTime();
        if (!locks.tryWrite("cleanup:scheduler", this::doCleanup)) {
            log.debug("另一个实例正在执行清理，本次跳过");
            return;
        }
        meters.timer("filehub.cleanup.duration").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }

    private void doCleanup() {
        int minutes = props.getUploadExpireMinutes();
        // 截止时间在 Java 里算，SQL 只做列与参数的比较：MySQL / H2 通用。
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        int removed = 0;
        for (FileInfo stale : fileInfoMapper.findExpiredUploading(cutoff)) {
            try {
                // 拿到写锁后做条件删除：查询到拿锁之间，可能有分片刚提交过并刷新了 update_time。
                // 条件 SQL 只删「仍在上传中且仍然过期」的行；删 0 行说明任务已复活/已完成。
                // 删库、删盘、删元数据都在同一把写锁内，晚到的分片请求拿到读锁后会看到任务不存在。
                int deleted = locks.write(stale.getFileId(), () -> {
                    int n = fileInfoMapper.deleteExpiredById(stale.getId(), cutoff);
                    if (n == 0) {
                        return 0;
                    }
                    // 先删元数据再删盘：中间崩溃时留下的是可被孤儿目录扫描发现的目录
                    fileChunkMapper.deleteByFileId(stale.getFileId());
                    chunkStorage.deleteChunks(stale.getFileId());
                    return n;
                });
                if (deleted == 0) {
                    continue;
                }
                removed++;
                meters.counter("filehub.cleanup.expired_uploads").increment();
                log.info("回收过期上传任务：{}（{}，{} 字节）",
                        stale.getFileId(), stale.getFileName(), stale.getFileSize());
            } catch (Exception e) {
                log.warn("回收上传任务失败：{}", stale.getFileId(), e);
            }
        }
        int orphanDirs = cleanupOrphanChunkDirs(Duration.ofMinutes(minutes));
        int orphanFiles = cleanupOrphanFinishedFiles(Duration.ofHours(props.getOrphanFileGraceHours()));
        int expiredTrash = cleanupExpiredTrash(Duration.ofDays(Math.max(1, props.getTrashRetentionDays())));
        int tempFiles = chunkStorage.cleanupTemporary(Duration.ofMinutes(minutes));
        meters.counter("filehub.cleanup.orphan_chunk_dirs").increment(orphanDirs);
        meters.counter("filehub.cleanup.orphan_files").increment(orphanFiles);
        meters.counter("filehub.cleanup.temp_files").increment(tempFiles);
        if (removed > 0 || orphanDirs > 0 || orphanFiles > 0 || expiredTrash > 0 || tempFiles > 0) {
            log.info("临时文件清理完成：过期任务 {} 个，过期回收站 {} 个，孤儿分片目录 {} 个，孤儿成品 {} 个，残留文件 {} 个",
                    removed, expiredTrash, orphanDirs, orphanFiles, tempFiles);
        }
    }

    private int cleanupExpiredTrash(Duration retention) {
        LocalDateTime cutoff = LocalDateTime.now().minus(retention);
        int removed = 0;
        for (FileInfo trashed : fileInfoMapper.findExpiredTrash(cutoff)) {
            try {
                int deleted = locks.write(trashed.getFileId(), () -> {
                    int n = fileInfoMapper.deleteExpiredTrashById(trashed.getId(), cutoff);
                    if (n == 0) {
                        return 0;
                    }
                    if (trashed.getStoragePath() != null) {
                        locks.writeVoid(contentKey(trashed.getFileHash(), trashed.getFileSize()), () -> {
                            if (fileInfoMapper.countByStoragePath(trashed.getStoragePath()) == 0) {
                                objectStorage.delete(trashed.getStoragePath());
                            }
                        });
                    }
                    return n;
                });
                if (deleted > 0) {
                    removed++;
                }
            } catch (Exception e) {
                log.warn("清理过期回收站记录失败：{}", trashed.getFileId(), e);
            }
        }
        return removed;
    }

    /**
     * 回收没有对应上传任务的 chunks/{fileId} 目录。
     *
     * <p>正常流程不会留下它们，但进程在「落盘后、写库前」或「删库后、删盘前」被杀时会有残留。
     * 只处理超过 upload-expire-minutes 没变化的目录，并在写锁内再查一次数据库，
     * 避免把扫描期间刚创建的任务删掉。</p>
     */
    private int cleanupOrphanChunkDirs(Duration age) {
        Set<String> uploading = new HashSet<>(fileInfoMapper.findUploadingFileIds());
        int removed = 0;
        for (String fileId : chunkStorage.listChunkDirs()) {
            if (uploading.contains(fileId) || !chunkStorage.chunkDirOlderThan(fileId, age)) {
                continue;
            }
            boolean deleted = locks.write(fileId, () -> {
                FileInfo row = findInfo(fileId);
                if (row != null && row.getStatus() == FileInfo.STATUS_UPLOADING) {
                    return false;
                }
                // 完成态任务理论上没有分片元数据了；崩溃残留的一并清掉
                fileChunkMapper.deleteByFileId(fileId);
                chunkStorage.deleteChunks(fileId);
                log.info("回收孤儿分片目录：{}", fileId);
                return true;
            });
            if (deleted) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 回收 files/ 下没有数据库记录引用的成品文件。
     *
     * <p>正常流程不会产生：put 成功后必定 markDone，删除最后引用时也会删盘。但进程在
     * 「put 完成、markDone 之前」被杀，或者数据库被手工改过，就会留下拿不回来的垃圾。
     * 三道保险防止误删：</p>
     * <ol>
     *   <li>只处理超过 {@code orphan-file-grace-hours}（默认 7 天）没变化的文件；</li>
     *   <li>拿全局 gc 写锁，与「秒传插入 / 合并去重复用」的 gc 读锁互斥；</li>
     *   <li>锁内再查一次引用计数，仍为 0 才删。</li>
     * </ol>
     *
     * <p>注意这里<b>只拿 gc 锁，不再拿 fileId 写锁</b>。否则会形成死锁环：
     * delete 拿 file→content，init 拿 content→gc，GC 若拿 file→gc，三个线程各持一把
     * 互相等待。GC 改成只拿 gc 后，锁序（file→content→gc，GC 只取 gc）无环；
     * 新插入的引用仍会被 gc 写锁挡住，引用计数不会在「数到 0」和「删盘」之间被改掉。</p>
     */
    private int cleanupOrphanFinishedFiles(Duration grace) {
        Set<String> referenced = new HashSet<>(fileInfoMapper.findAllStoragePaths());
        int removed = 0;
        for (String path : objectStorage.listFilePaths()) {
            if (referenced.contains(path) || !objectStorage.fileOlderThan(path, grace)) {
                continue;
            }
            boolean deleted = locks.write(GC_LOCK, () -> {
                if (fileInfoMapper.countByStoragePath(path) > 0) {
                    return false;
                }
                if (!objectStorage.delete(path)) {
                    return false;
                }
                log.info("回收孤儿成品文件：{}", path);
                return true;
            });
            if (deleted) {
                removed++;
            }
        }
        return removed;
    }

    public InputStream openForRead(FileInfo info) {
        requireReadable(info);
        try {
            return objectStorage.open(info.getStoragePath());
        } catch (IOException e) {
            throw new BizException(20006, "读取文件失败：" + e.getMessage());
        }
    }

    /**
     * 从指定偏移打开成品文件，供 HTTP Range（尤其是多段 Range）使用。
     * 长度由调用方用 BoundedInputStream 之类限制；这里只负责校验和定位。
     */
    public InputStream openRangeForRead(FileInfo info, long offset, long length) {
        requireReadable(info);
        try {
            return objectStorage.openRange(info.getStoragePath(), offset, length);
        } catch (IOException e) {
            throw new BizException(20006, "读取文件失败：" + e.getMessage());
        }
    }

    public FileInfo requireReadableInfo(String fileId, UserPrincipal user) {
        FileInfo info = requireInfo(fileId, user);
        requireReadable(info);
        return info;
    }

    private void requireReadable(FileInfo info) {
        if (info.getStatus() != FileInfo.STATUS_DONE) {
            throw new BizException(10006, "文件尚未上传完成或已在回收站，无法访问");
        }
        if (!isStorageIntact(info)) {
            throw new BizException(20006, "存储中的文件缺失或大小不符，无法下载");
        }
    }

    /** 移入回收站（仅已完成文件；保留物理文件引用以便恢复） */
    public void moveToTrash(String fileId, UserPrincipal user) {
        LocalDiskStorage.requireSafeId(fileId);
        locks.writeVoid(fileId, () -> {
            FileInfo info = requireInfo(fileId, user);
            if (info.getStatus() != FileInfo.STATUS_DONE) {
                throw new BizException(10006, "只有已完成的文件可以移入回收站");
            }
            fileInfoMapper.moveToTrash(info.getId(), LocalDateTime.now());
        });
    }

    /** 从回收站恢复到原路径 */
    public FileVO restoreFromTrash(String fileId, UserPrincipal user) {
        LocalDiskStorage.requireSafeId(fileId);
        return locks.write(fileId, () -> {
            FileInfo info = requireInfo(fileId, user);
            if (info.getStatus() != FileInfo.STATUS_TRASH) {
                throw new BizException(10006, "该文件不在回收站中");
            }
            fileInfoMapper.restoreFromTrash(info.getId(), LocalDateTime.now());
            return FileVO.from(requireInfo(fileId, user));
        });
    }

    public PageResponse<FileVO> listTrash(int page, int size, UserPrincipal user) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        LambdaQueryWrapper<FileInfo> qw = new LambdaQueryWrapper<>();
        applyOwnerScope(qw, user, null);
        qw.eq(FileInfo::getStatus, FileInfo.STATUS_TRASH);
        qw.orderByDesc(FileInfo::getDeletedAt).orderByDesc(FileInfo::getId);
        Page<FileInfo> result = fileInfoMapper.selectPage(new Page<>(safePage, safeSize), qw);
        return new PageResponse<>(
                result.getRecords().stream().map(FileVO::from).toList(),
                result.getTotal(), safePage, safeSize);
    }

    public int emptyTrash(UserPrincipal user) {
        LambdaQueryWrapper<FileInfo> qw = new LambdaQueryWrapper<>();
        applyOwnerScope(qw, user, null);
        qw.eq(FileInfo::getStatus, FileInfo.STATUS_TRASH);
        List<FileInfo> trashed = fileInfoMapper.selectList(qw);
        int count = 0;
        for (FileInfo info : trashed) {
            try {
                delete(info.getFileId(), user);
                count++;
            } catch (RuntimeException e) {
                log.warn("清空回收站项失败 fileId={}", info.getFileId(), e);
            }
        }
        return count;
    }

    /** 分享转存：利用内容寻址在接收者空间生成新逻辑记录，零数据拷贝 */
    public FileVO createSharedCopy(FileInfo source, UserPrincipal receiver) {
        return locks.write(contentKey(source.getFileHash(), source.getFileSize()), () ->
                locks.read(GC_LOCK, () -> {
                    if (!isStorageIntact(source)) {
                        throw new BizException(42005, "文件已失效，无法转存");
                    }
                    checkQuota(receiver, source.getFileSize());
                    String newFileId = UUID.randomUUID().toString();
                    LocalDateTime now = LocalDateTime.now();
                    FileInfo row = new FileInfo();
                    row.setFileId(newFileId);
                    row.setFileName(source.getFileName());
                    row.setRelativePath("");
                    row.setFileSize(source.getFileSize());
                    row.setFileHash(source.getFileHash());
                    row.setChunkSize(source.getChunkSize());
                    row.setChunkTotal(source.getChunkTotal());
                    row.setStoragePath(source.getStoragePath());
                    row.setStatus(FileInfo.STATUS_DONE);
                    row.setOwnerId(receiver.id());
                    row.setCreateTime(now);
                    row.setUpdateTime(now);
                    fileInfoMapper.insert(row);
                    return FileVO.from(row);
                }));
    }

    /**
     * 直接流式写入成品（供 WebDAV PUT 与桌面同步使用）：
     * 边落临时文件边算 SHA-256，校验配额后按内容寻址去重落入存储后端；
     * 若同路径已有文件，新文件落盘后替换旧逻辑记录。
     */
    public FileVO storeStream(String fileName, String relativePath, InputStream in, UserPrincipal user) {
        String tempId = UUID.randomUUID().toString();
        Path merged = null;
        try {
            // 先查同路径旧文件与可用额度，流式写入过程中实时截断超限请求，避免打满临时磁盘
            List<FileInfo> existingSamePath = findByExactPath(user.id(), fileName, relativePath);
            long existingReplacedBytes = existingSamePath.stream().mapToLong(FileInfo::getFileSize).sum();

            long maxStream = props.getMaxStreamUploadBytes() > 0
                    ? props.getMaxStreamUploadBytes()
                    : Long.MAX_VALUE;
            long remaining = quotaService != null ? quotaService.remainingBytesFor(user) : -1L;
            long quotaCap = Long.MAX_VALUE;
            if (remaining >= 0) {
                quotaCap = remaining > Long.MAX_VALUE - existingReplacedBytes
                        ? Long.MAX_VALUE
                        : remaining + existingReplacedBytes;
            }
            long maxAllowed = Math.min(maxStream, quotaCap);
            boolean quotaTightest = remaining >= 0 && quotaCap <= maxStream;

            merged = chunkStorage.createMergeTmpFile(tempId);
            MessageDigest digest = HashUtil.sha256();
            long size = 0;
            try (DigestInputStream digestIn = new DigestInputStream(in, digest);
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(merged), 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                int read;
                while ((read = digestIn.read(buf)) != -1) {
                    size += read;
                    if (size > maxAllowed) {
                        if (quotaTightest) {
                            throw new BizException(41001, "存储空间不足：流式上传超过可用配额上限");
                        }
                        throw new BizException(10001, "单次流式上传超过大小上限");
                    }
                    out.write(buf, 0, read);
                }
            } catch (BizException e) {
                deleteQuietly(merged);
                throw e;
            }
            String hash = HexFormat.of().formatHex(digest.digest());

            if (size > existingReplacedBytes) {
                checkQuota(user, size - existingReplacedBytes);
            }

            final Path mergedFile = merged;
            final long finalSize = size;
            int chunkSize = (int) Math.max(1, props.getChunkSize());
            int chunkTotal = calcChunkTotal(finalSize, chunkSize);
            String newFileId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();

            FileInfo inserted = locks.write(contentKey(hash, finalSize), () ->
                    locks.read(GC_LOCK, () -> {
                        FileInfo done = findUsableDone(hash, finalSize);
                        String path;
                        if (done != null) {
                            deleteQuietly(mergedFile);
                            path = done.getStoragePath();
                        } else {
                            path = chunkStorage.buildRelativePath(newFileId);
                            try {
                                objectStorage.put(path, mergedFile);
                            } catch (IOException e) {
                                deleteQuietly(mergedFile);
                                throw new BizException(20005, "写入存储失败：" + e.getMessage());
                            }
                        }
                        FileInfo row = new FileInfo();
                        row.setFileId(newFileId);
                        row.setFileName(fileName);
                        row.setRelativePath(relativePath == null ? "" : relativePath);
                        row.setFileSize(finalSize);
                        row.setFileHash(hash);
                        row.setChunkSize(chunkSize);
                        row.setChunkTotal(chunkTotal);
                        row.setStoragePath(path);
                        row.setStatus(FileInfo.STATUS_DONE);
                        row.setOwnerId(user.id());
                        row.setCreateTime(now);
                        row.setUpdateTime(now);
                        fileInfoMapper.insert(row);
                        return row;
                    }));

            for (FileInfo old : existingSamePath) {
                try {
                    delete(old.getFileId(), user);
                } catch (RuntimeException e) {
                    log.warn("覆盖旧文件失败 oldId={}", old.getFileId(), e);
                }
            }
            return FileVO.from(inserted);
        } catch (IOException e) {
            deleteQuietly(merged);
            throw new BizException(20005, "写入文件流失败：" + e.getMessage());
        }
    }

    private List<FileInfo> findByExactPath(Long ownerId, String fileName, String relativePath) {
        String rel = relativePath == null ? "" : relativePath;
        return fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getOwnerId, ownerId)
                .eq(FileInfo::getFileName, fileName)
                .eq(FileInfo::getRelativePath, rel)
                .eq(FileInfo::getStatus, FileInfo.STATUS_DONE))
                .stream()
                .filter(f -> fileName.equals(f.getFileName()) && rel.equals(f.getRelativePath()))
                .toList();
    }

    private void checkQuota(UserPrincipal user, long additionalBytes) {
        if (quotaService != null) {
            quotaService.checkCanAllocate(user, additionalBytes);
        }
    }

    /**
     * 删除。因为秒传/去重会让多条逻辑记录共享同一份物理文件，
     * <b>不能看到一条记录就直接删磁盘文件</b>——得先数一下还有没有别人在用。
     */
    public void delete(String fileId, UserPrincipal user) {
        LocalDiskStorage.requireSafeId(fileId);
        locks.writeVoid(fileId, () -> {
            FileInfo info = requireInfo(fileId, user);
            fileInfoMapper.deleteById(info.getId());

            if (info.getStatus() == FileInfo.STATUS_DONE || info.getStatus() == FileInfo.STATUS_TRASH) {
                // 与秒传插入共用内容锁：删除行和数引用之间，不能再插进一条新引用，
                // 否则会出现「数到 0 就删盘，但新行已经指向这个物理文件」。
                locks.writeVoid(contentKey(info.getFileHash(), info.getFileSize()), () -> {
                    int stillReferenced = fileInfoMapper.countByStoragePath(info.getStoragePath());
                    if (stillReferenced == 0) {
                        objectStorage.delete(info.getStoragePath());
                        log.info("最后一个引用已删除，物理文件一并清理：{}", info.getStoragePath());
                    } else {
                        log.info("物理文件仍被 {} 条记录引用，予以保留：{}", stillReferenced, info.getStoragePath());
                    }
                });
            } else {
                // 先清元数据再删目录；若数据库失败，保留目录供孤儿清理任务重试。
                fileChunkMapper.deleteByFileId(fileId);
                chunkStorage.deleteChunks(fileId);
            }
        });
    }

    // ==================================================================

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主流程
        }
    }

    private static String abbreviate(List<Integer> list) {
        List<Integer> head = list.size() > 10 ? list.subList(0, 10) : list;
        return head + (list.size() > 10 ? " ...(共 " + list.size() + " 个)" : "");
    }
}
