package com.yuzhuo.filehub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.common.FileLockRegistry;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.dto.CheckResponse;
import com.yuzhuo.filehub.dto.FileVO;
import com.yuzhuo.filehub.dto.InitUploadRequest;
import com.yuzhuo.filehub.dto.InitUploadResponse;
import com.yuzhuo.filehub.dto.StatsResponse;
import com.yuzhuo.filehub.dto.UploadStatusResponse;
import com.yuzhuo.filehub.entity.FileChunk;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.mapper.FileChunkMapper;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.storage.LocalDiskStorage;
import com.yuzhuo.filehub.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class FileStorageService {

    private final FileInfoMapper fileInfoMapper;
    private final FileChunkMapper fileChunkMapper;
    private final LocalDiskStorage storage;
    private final StorageProperties props;
    private final FileLockRegistry locks;

    // ==================================================================
    // 1. 秒传预检
    // ==================================================================

    /**
     * 上传前先问一句「这个文件你库里有没有」。命中就一个字节都不用传。
     */
    public CheckResponse check(String hash, long size) {
        FileInfo hit = fileInfoMapper.findDoneByHashAndSize(hash, size);
        return new CheckResponse(hit != null, hit == null ? null : hit.getFileId());
    }

    // ==================================================================
    // 2. 初始化上传
    // ==================================================================

    /**
     * 初始化。三种走向：
     * <ol>
     *   <li><b>秒传命中</b>：直接建一条已完成记录，共享已有物理文件；</li>
     *   <li><b>断点续传</b>：同 hash+size 已有上传中的任务，复用它并回传已传分片；</li>
     *   <li>全新上传：建任务。</li>
     * </ol>
     */
    public InitUploadResponse init(InitUploadRequest req) {
        int chunkSize = (int) props.getChunkSize();
        int chunkTotal = calcChunkTotal(req.fileSize(), chunkSize);
        String fileHash = req.fileHash().toLowerCase();

        // --- 情况 1：秒传 ---
        // 同一份内容的「新增引用」和「删除最后一条引用」必须互斥：否则删除方刚数完
        // 引用为 0，秒传方又插进来一条指向同一物理文件的记录，就会得到一条打不开的完成记录。
        InitUploadResponse instant = locks.write(contentKey(fileHash, req.fileSize()), () -> {
            FileInfo done = fileInfoMapper.findDoneByHashAndSize(fileHash, req.fileSize());
            if (done == null) {
                return null;
            }
            String newFileId = UUID.randomUUID().toString();
            FileInfo row = newRow(newFileId, req, chunkSize, chunkTotal);
            row.setStatus(FileInfo.STATUS_DONE);
            // 新记录指向同一份物理文件：磁盘上不再存第二份
            row.setStoragePath(done.getStoragePath());
            fileInfoMapper.insert(row);
            log.info("秒传命中：{} -> 复用 {}", req.fileName(), done.getStoragePath());
            return new InitUploadResponse(newFileId, true, chunkSize, chunkTotal, req.fileSize(), List.of());
        });
        if (instant != null) {
            return instant;
        }

        // --- 情况 2：有同一文件的上传中任务（刷新页面 / 换标签页） ---
        FileInfo ongoing = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getFileHash, fileHash)
                .eq(FileInfo::getFileSize, req.fileSize())
                .eq(FileInfo::getStatus, FileInfo.STATUS_UPLOADING)
                .orderByDesc(FileInfo::getId)
                .last("LIMIT 1"));
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
        String fileId = UUID.randomUUID().toString();
        FileInfo row = newRow(fileId, req, chunkSize, chunkTotal);
        fileInfoMapper.insert(row);
        log.info("新建上传任务：{}（{} 字节，{} 片）", fileId, req.fileSize(), chunkTotal);
        return new InitUploadResponse(fileId, false, chunkSize, chunkTotal, req.fileSize(), List.of());
    }

    private FileInfo newRow(String fileId, InitUploadRequest req, int chunkSize, int chunkTotal) {
        FileInfo row = new FileInfo();
        row.setFileId(fileId);
        row.setFileName(req.fileName());
        row.setFileSize(req.fileSize());
        row.setFileHash(req.fileHash().toLowerCase());
        row.setChunkSize(chunkSize);
        row.setChunkTotal(chunkTotal);
        row.setStatus(FileInfo.STATUS_UPLOADING);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        return row;
    }

    /** 分片总数由服务端推导，不接受前端传入——前端算错一次就会合并出坏文件 */
    static int calcChunkTotal(long fileSize, int chunkSize) {
        if (fileSize == 0) {
            return 0;
        }
        return (int) ((fileSize + chunkSize - 1) / chunkSize);
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
    public void saveChunk(String fileId, int index, InputStream in, String expectedChunkHash) {
        LocalDiskStorage.requireSafeId(fileId);
        // 读锁：同一任务的多个分片可以并发落盘，但合并/删除会等这些写入完成再拿写锁
        locks.readVoid(fileId, () -> doSaveChunk(fileId, index, in, expectedChunkHash));
    }

    private void doSaveChunk(String fileId, int index, InputStream in, String expectedChunkHash) {
        FileInfo info = requireInfo(fileId);

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
            result = storage.saveChunk(fileId, index, in, expectedChunkHash, expectedSize);
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
            storage.deleteChunk(fileId, index);
            fileChunkMapper.deleteChunk(fileId, index);
            FileInfo after = findInfo(fileId);
            if (after != null && after.getStatus() == FileInfo.STATUS_DONE) {
                log.info("文件 {} 已完成，迟到的分片 {} 已丢弃", fileId, index);
                return;
            }
            throw new BizException(10005, "上传任务已结束或已过期，请重新初始化");
        }
    }

    public void saveChunk(String fileId, int index, InputStream in) {
        saveChunk(fileId, index, in, null);
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
    public FileVO complete(String fileId) {
        LocalDiskStorage.requireSafeId(fileId);
        // 写锁覆盖整个「检查 -> 合并 -> markDone -> 删分片」过程。
        // 两个并发 complete 会被串行化，后到的进来时能看到完成态并直接返回。
        // 注意这里不再用 @Transactional：方法里的每次 mapper 调用各自提交，
        // 锁释放时数据库状态已经对其他线程可见，不会出现「锁放了但事务还没提交」的窗口。
        return locks.write(fileId, () -> doComplete(fileId));
    }

    private FileVO doComplete(String fileId) {
        FileInfo info = requireInfo(fileId);

        if (info.getStatus() == FileInfo.STATUS_DONE) {
            log.info("文件 {} 已经是完成态，直接返回", fileId);
            return FileVO.from(info);
        }

        // 以磁盘为准判断齐没齐：数据库说传过、但文件丢了（比如被清理过），
        // 这里能如实发现并要求重传，而不是拼出一个缺片的坏文件
        List<Integer> onDisk = storage.listChunksOnDisk(fileId);
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
            merged = storage.createMergeTmpFile(fileId);
            var digest = HashUtil.sha256();
            try (OutputStream fileOut = Files.newOutputStream(merged);
                 BufferedOutputStream buffered = new BufferedOutputStream(fileOut, 1 << 16);
                 DigestOutputStream digestOut = new DigestOutputStream(buffered, digest)) {
                for (int i = 0; i < info.getChunkTotal(); i++) {
                    // 每片单独算一次摘要，同时把字节写进整文件摘要流：
                    // 磁盘只读一遍，却能同时回答"这一片对不对"和"整个文件对不对"
                    MessageDigest chunkDigest = HashUtil.sha256();
                    long chunkBytes;
                    try (InputStream chunkIn = Files.newInputStream(storage.chunkFile(fileId, i));
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
                storage.deleteChunk(fileId, i);
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
        String storagePath = locks.write(contentKey(info.getFileHash(), info.getFileSize()), () -> {
            FileInfo existing = fileInfoMapper.findDoneByHashAndSize(info.getFileHash(), info.getFileSize());
            String path;
            if (existing != null) {
                deleteQuietly(mergedFile);
                path = existing.getStoragePath();
                log.info("合并后去重：{} 复用已有物理文件 {}", fileId, path);
            } else {
                path = storage.buildRelativePath(fileId);
                try {
                    storage.put(path, mergedFile);
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
        });
        storage.deleteChunks(fileId);

        FileInfo after = requireInfo(fileId);
        log.info("文件 {} 合并完成：{} 字节，{} 片，hash={}", fileId, actualSize, info.getChunkTotal(), actualHash);
        return FileVO.from(after);
    }

    // ==================================================================
    // 5. 查询 / 下载 / 删除
    // ==================================================================

    public List<FileVO> list() {
        return fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                        .orderByDesc(FileInfo::getId)
                        .last("LIMIT 200"))
                .stream().map(FileVO::from).toList();
    }

    public FileInfo requireInfo(String fileId) {
        FileInfo info = findInfo(fileId);
        if (info == null) {
            throw new BizException(10005, "文件不存在：" + fileId);
        }
        return info;
    }

    private FileInfo findInfo(String fileId) {
        return fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getFileId, fileId));
    }

    /** 内容锁的 key：同一 (hash, size) 在磁盘上只有一份成品，所有引用都靠它互斥 */
    private static String contentKey(String hash, long size) {
        return "content:" + hash.toLowerCase() + ":" + size;
    }

    /** 已落盘的分片序号（以磁盘为准） */
    public List<Integer> uploadedIndexes(String fileId) {
        return storage.listChunksOnDisk(fileId);
    }

    /**
     * 上传任务状态。以磁盘为准回答"现在到底有哪些片"——
     * 暂停后继续、页面刷新重连、合并发现坏片后补传，都靠这个接口重新对齐。
     */
    public UploadStatusResponse status(String fileId) {
        LocalDiskStorage.requireSafeId(fileId);
        FileInfo info = requireInfo(fileId);
        return new UploadStatusResponse(
                info.getFileId(),
                info.getFileName(),
                info.getFileSize(),
                info.getStatus(),
                info.getStatus() == FileInfo.STATUS_DONE,
                info.getChunkSize(),
                info.getChunkTotal(),
                storage.listChunksOnDisk(fileId));
    }

    public StatsResponse stats() {
        long done = fileInfoMapper.countDone();
        long uploading = fileInfoMapper.countUploading();
        long logical = fileInfoMapper.sumLogicalBytes();
        long physical = fileInfoMapper.sumPhysicalBytes();
        return new StatsResponse(done, uploading, logical, physical, Math.max(0, logical - physical));
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
        int minutes = props.getUploadExpireMinutes();
        int removed = 0;
        for (FileInfo stale : fileInfoMapper.findExpiredUploading(minutes)) {
            try {
                // 拿到写锁后做条件删除：查询到拿锁之间，可能有分片刚提交过并刷新了 update_time。
                // 条件 SQL 只删「仍在上传中且仍然过期」的行；删 0 行说明任务已复活/已完成。
                // 删库、删盘、删元数据都在同一把写锁内，晚到的分片请求拿到读锁后会看到任务不存在。
                int deleted = locks.write(stale.getFileId(), () -> {
                    int n = fileInfoMapper.deleteExpiredById(stale.getId(), minutes);
                    if (n == 0) {
                        return 0;
                    }
                    storage.deleteChunks(stale.getFileId());
                    fileChunkMapper.deleteByFileId(stale.getFileId());
                    return n;
                });
                if (deleted == 0) {
                    continue;
                }
                removed++;
                log.info("回收过期上传任务：{}（{}，{} 字节）",
                        stale.getFileId(), stale.getFileName(), stale.getFileSize());
            } catch (Exception e) {
                log.warn("回收上传任务失败：{}", stale.getFileId(), e);
            }
        }
        int orphanDirs = cleanupOrphanChunkDirs(Duration.ofMinutes(minutes));
        int tempFiles = storage.cleanupTemporary(Duration.ofMinutes(minutes));
        if (removed > 0 || orphanDirs > 0 || tempFiles > 0) {
            log.info("临时文件清理完成：过期任务 {} 个，孤儿分片目录 {} 个，残留文件 {} 个",
                    removed, orphanDirs, tempFiles);
        }
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
        for (String fileId : storage.listChunkDirs()) {
            if (uploading.contains(fileId) || !storage.chunkDirOlderThan(fileId, age)) {
                continue;
            }
            boolean deleted = locks.write(fileId, () -> {
                FileInfo row = findInfo(fileId);
                if (row != null && row.getStatus() == FileInfo.STATUS_UPLOADING) {
                    return false;
                }
                storage.deleteChunks(fileId);
                // 完成态任务理论上没有分片元数据了；崩溃残留的一并清掉
                fileChunkMapper.deleteByFileId(fileId);
                log.info("回收孤儿分片目录：{}", fileId);
                return true;
            });
            if (deleted) {
                removed++;
            }
        }
        return removed;
    }

    public InputStream openForRead(FileInfo info) {
        if (info.getStatus() != FileInfo.STATUS_DONE) {
            throw new BizException(10006, "文件尚未上传完成，无法下载");
        }
        try {
            return storage.open(info.getStoragePath());
        } catch (IOException e) {
            throw new BizException(20006, "读取文件失败：" + e.getMessage());
        }
    }

    /**
     * 删除。因为秒传/去重会让多条逻辑记录共享同一份物理文件，
     * <b>不能看到一条记录就直接删磁盘文件</b>——得先数一下还有没有别人在用。
     */
    public void delete(String fileId) {
        LocalDiskStorage.requireSafeId(fileId);
        locks.writeVoid(fileId, () -> {
            FileInfo info = requireInfo(fileId);
            fileInfoMapper.deleteById(info.getId());

            if (info.getStatus() == FileInfo.STATUS_DONE) {
                // 与秒传插入共用内容锁：删除行和数引用之间，不能再插进一条新引用，
                // 否则会出现「数到 0 就删盘，但新行已经指向这个物理文件」。
                locks.writeVoid(contentKey(info.getFileHash(), info.getFileSize()), () -> {
                    int stillReferenced = fileInfoMapper.countByStoragePath(info.getStoragePath());
                    if (stillReferenced == 0) {
                        storage.delete(info.getStoragePath());
                        log.info("最后一个引用已删除，物理文件一并清理：{}", info.getStoragePath());
                    } else {
                        log.info("物理文件仍被 {} 条记录引用，予以保留：{}", stillReferenced, info.getStoragePath());
                    }
                });
            } else {
                storage.deleteChunks(fileId);
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
