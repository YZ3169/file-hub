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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /** 落盘一个分片。先写 .tmp 再原子改名，避免"半个分片"被当成完整分片 */
    public long saveChunk(String fileId, int index, InputStream in) throws IOException {
        Path target = chunkFile(fileId, index);
        Files.createDirectories(target.getParent());
        Path incoming = target.resolveSibling(index + ".part.incoming");
        try (OutputStream out = Files.newOutputStream(incoming)) {
            in.transferTo(out);
        }
        Files.move(incoming, target, StandardCopyOption.REPLACE_EXISTING);
        return Files.size(target);
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
                    .map(Integer::parseInt)
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
