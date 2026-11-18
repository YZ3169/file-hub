package com.yuzhuo.filehub.storage;

import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.yuzhuo.filehub.util.HashUtil;

/**
 * 本地磁盘实现。
 *
 * <p>目录结构：
 * <pre>
 *   {root}/files/yyyy/MM/dd/{fileId}   合并完成的成品
 *   {root}/chunks/{fileId}/{index}.part 上传中的临时分片
 *   {root}/tmp/{fileId}.merging         合并过程中的中间文件
 * </pre>
 * 成品按日期分目录，是为了避免所有文件挤在一个目录里——单个目录文件数上万之后，
 * 连 ls / 目录遍历都会明显变慢。</p>
 */
@Slf4j
@Component
public class LocalDiskStorage implements StorageBackend {

    /**
     * fileId 是客户端回传的（init 发下去，chunk 传回来）。
     * 它会被直接拼进文件路径，所以必须严格校验形状 —— 否则 `..%2f..%2f` 这类
     * 构造出来的 id 就能把分片写到存储根目录之外去。服务端只认自己发过的 UUID 形状。
     */
    private static final Pattern SAFE_ID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final Path root;
    private final Path filesDir;
    private final Path chunksDir;
    private final Path tmpDir;

    public LocalDiskStorage(StorageProperties props) {
        this.root = Paths.get(props.getRoot()).toAbsolutePath().normalize();
        this.filesDir = root.resolve("files");
        this.chunksDir = root.resolve("chunks");
        this.tmpDir = root.resolve("tmp");
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(filesDir);
        Files.createDirectories(chunksDir);
        Files.createDirectories(tmpDir);
        log.info("存储根目录就绪：{}", root);
    }

    /** 校验并返回安全的 fileId，不合法直接拒绝 */
    public static String requireSafeId(String fileId) {
        if (fileId == null || !SAFE_ID.matcher(fileId).matches()) {
            throw new BizException(10002, "非法的文件标识：" + fileId);
        }
        return fileId;
    }

    // ------------------------------------------------------------------
    // 分片（临时，只在本机有意义，所以不进 StorageBackend 接口）
    // ------------------------------------------------------------------

    public Path chunkFile(String fileId, int index) {
        return chunksDir.resolve(requireSafeId(fileId)).resolve(index + ".part");
    }

    /**
     * 落盘一个分片并计算它的 SHA-256。
     *
     * <p>三个细节都是为了「传输过程中出问题」准备的：</p>
     * <ul>
     *   <li><b>先写临时文件，校验通过才原子改名</b>：不把半截分片暴露成完整分片；</li>
     *   <li><b>临时文件名带 UUID</b>：同一片被并发重传时不会互相覆盖对方的 .incoming；</li>
     *   <li><b>摘要不匹配时不覆盖旧分片</b>：旧片可能是好的，坏的是这次收到的字节，
     *       删掉 incoming 即可，不能让一次坏重传把已经正确的分片也弄没。</li>
     * </ul>
     */
    public ChunkWriteResult saveChunk(String fileId, int index, InputStream in,
                                      String expectedSha256, long expectedSize) throws IOException {
        Path target = chunkFile(fileId, index);
        Files.createDirectories(target.getParent());
        Path incoming = target.resolveSibling(index + "." + UUID.randomUUID() + ".part.incoming");

        MessageDigest digest = HashUtil.sha256();
        long written;
        try (InputStream digestIn = new DigestInputStream(in, digest);
             OutputStream out = Files.newOutputStream(incoming)) {
            written = digestIn.transferTo(out);
        }
        String actual = HexFormat.of().formatHex(digest.digest());

        boolean hashOk = expectedSha256 == null || expectedSha256.isBlank()
                || expectedSha256.equalsIgnoreCase(actual);
        boolean sizeOk = expectedSize < 0 || expectedSize == written;
        if (!hashOk || !sizeOk) {
            Files.deleteIfExists(incoming);
            return new ChunkWriteResult(written, actual, false);
        }

        Files.move(incoming, target, StandardCopyOption.REPLACE_EXISTING);
        return new ChunkWriteResult(written, actual, true);
    }

    public ChunkWriteResult saveChunk(String fileId, int index, InputStream in, String expectedSha256)
            throws IOException {
        return saveChunk(fileId, index, in, expectedSha256, -1);
    }

    public ChunkWriteResult saveChunk(String fileId, int index, InputStream in) throws IOException {
        return saveChunk(fileId, index, in, null, -1);
    }

    /** 一个分片的落盘结果：实际字节数、服务端复算的摘要、是否通过校验并原子落盘 */
    public record ChunkWriteResult(long size, String sha256, boolean accepted) {
    }

    /** 删除单个分片（合并阶段发现它损坏时用），不存在也不报错 */
    public void deleteChunk(String fileId, int index) {
        try {
            Files.deleteIfExists(chunkFile(fileId, index));
        } catch (IOException e) {
            log.warn("删除损坏分片失败：fileId={} index={}", fileId, index, e);
        }
    }

    public boolean chunkExists(String fileId, int index) {
        return Files.exists(chunkFile(fileId, index));
    }

    public long chunkLength(String fileId, int index) {
        try {
            return Files.size(chunkFile(fileId, index));
        } catch (IOException e) {
            return -1;
        }
    }

    /** 磁盘上实际存在的分片序号——用来和数据库里的记录对账 */
    public List<Integer> listChunksOnDisk(String fileId) {
        Path dir = chunksDir.resolve(requireSafeId(fileId));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(Path::getFileName)
                    .map(Path::toString)
                    .filter(n -> n.endsWith(".part"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .filter(n -> n.chars().allMatch(Character::isDigit))
                    .map(Integer::parseInt)
                    .filter(i -> i >= 0)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new BizException(20002, "读取分片目录失败：" + e.getMessage());
        }
    }

    public Path createMergeTmpFile(String fileId) throws IOException {
        Files.createDirectories(tmpDir);
        return Files.createTempFile(tmpDir, requireSafeId(fileId) + "-", ".merging");
    }

    /** chunks/ 下所有合法任务目录名（只认 UUID 形状，避免误删别的东西） */
    public List<String> listChunkDirs() {
        if (!Files.isDirectory(chunksDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(chunksDir)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> SAFE_ID.matcher(n).matches())
                    .toList();
        } catch (IOException e) {
            log.warn("读取分片根目录失败：{}", chunksDir, e);
            return List.of();
        }
    }

    /**
     * files/ 下所有成品文件的相对路径（{@code yyyy/MM/dd/{fileId}}，统一用 / 分隔）。
     * 只返回文件名是 UUID 形状的普通文件，避免把用户手工放进来的东西当成成品清理。
     */
    public List<String> listFilePaths() {
        if (!Files.isDirectory(filesDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(filesDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> SAFE_ID.matcher(p.getFileName().toString()).matches())
                    .map(p -> filesDir.relativize(p).toString().replace('\\', '/'))
                    .toList();
        } catch (IOException e) {
            log.warn("读取成品根目录失败：{}", filesDir, e);
            return List.of();
        }
    }

    /** 成品文件是否已经超过 age 没有任何变化（用于孤儿文件回收的宽限期） */
    public boolean fileOlderThan(String relativePath, Duration age) {
        try {
            FileTime t = Files.getLastModifiedTime(resolveSafe(relativePath));
            return t.toInstant().isBefore(Instant.now().minus(age));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 任务目录是否已经超过 age 没有任何变化。
     * 每片原子改名都会更新目录 mtime，所以长期不变的目录可以判定为无人使用。
     */
    public boolean chunkDirOlderThan(String fileId, Duration age) {
        try {
            FileTime t = Files.getLastModifiedTime(chunksDir.resolve(requireSafeId(fileId)));
            return t.toInstant().isBefore(Instant.now().minus(age));
        } catch (IOException e) {
            return false;
        }
    }

    /** 删掉一个上传任务的全部分片目录（合并完 / 删除文件时调用） */
    public void deleteChunks(String fileId) {
        Path dir = chunksDir.resolve(requireSafeId(fileId));
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("清理临时分片失败：{}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理分片目录失败：{}", dir, e);
        }
    }

    /**
     * 回收两类「进程被杀/网络中断留下的垃圾」：
     * <ul>
     *   <li>{@code tmp/*.merging}：合并到一半的临时文件；</li>
     *   <li>{@code chunks/*}{@code /N.<uuid>.part.incoming}：分片写了一半没来得及改名。</li>
     * </ul>
     * 判据是文件修改时间，不用数据库——这些文件本来就没进过数据库。
     */
    public int cleanupTemporary(Duration age) {
        Instant cutoff = Instant.now().minus(age);
        int[] deleted = {0};

        try (Stream<Path> s = Files.list(tmpDir)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                if (olderThan(p, cutoff) && deleteQuietly(p)) {
                    deleted[0]++;
                }
            });
        } catch (IOException e) {
            log.warn("清理合并临时目录失败：{}", tmpDir, e);
        }

        if (Files.isDirectory(chunksDir)) {
            try (Stream<Path> s = Files.walk(chunksDir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".part.incoming"))
                        .forEach(p -> {
                            if (olderThan(p, cutoff) && deleteQuietly(p)) {
                                deleted[0]++;
                            }
                        });
            } catch (IOException e) {
                log.warn("清理残留 .incoming 分片失败：{}", chunksDir, e);
            }
        }
        return deleted[0];
    }

    private static boolean olderThan(Path p, Instant cutoff) {
        try {
            FileTime t = Files.getLastModifiedTime(p);
            return t.toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean deleteQuietly(Path p) {
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("删除临时文件失败：{}", p, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 成品（StorageBackend 实现）
    // ------------------------------------------------------------------

    /**
     * 生成成品的相对路径：yyyy/MM/dd/{fileId}
     * fileId 就是 UUID，天然不重名，不需要再拼原始文件名——原始文件名里可能有
     * 空格、中文、甚至 `../`，把它放进路径是自找麻烦。
     */
    public String buildRelativePath(String fileId) {
        requireSafeId(fileId);
        java.time.LocalDate d = java.time.LocalDate.now();
        return String.format("%04d/%02d/%02d/%s", d.getYear(), d.getMonthValue(), d.getDayOfMonth(), fileId);
    }

    private Path resolveSafe(String relativePath) {
        Path p = filesDir.resolve(relativePath).normalize();
        if (!p.startsWith(filesDir)) {
            // 防目录穿越：拼出来的路径必须仍在 files/ 里面
            throw new BizException(10003, "非法的存储路径");
        }
        return p;
    }

    @Override
    public void put(String relativePath, Path source) throws IOException {
        Path target = resolveSafe(relativePath);
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // 跨盘符时 ATOMIC_MOVE 不支持，退化成普通移动
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public InputStream open(String relativePath) throws IOException {
        return Files.newInputStream(resolveSafe(relativePath));
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.exists(resolveSafe(relativePath));
    }

    @Override
    public long size(String relativePath) {
        try {
            return Files.size(resolveSafe(relativePath));
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public boolean delete(String relativePath) {
        try {
            return Files.deleteIfExists(resolveSafe(relativePath));
        } catch (IOException e) {
            log.warn("删除成品文件失败：{}", relativePath, e);
            return false;
        }
    }

    @Override
    public String backendName() {
        return "local-disk:" + root;
    }
}
