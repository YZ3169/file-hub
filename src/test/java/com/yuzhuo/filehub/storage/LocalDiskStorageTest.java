package com.yuzhuo.filehub.storage;

import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDiskStorageTest {

    @TempDir
    Path root;

    LocalDiskStorage storage;

    static final String ID = "1f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";
    static final String OTHER_ID = "2f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties props = new StorageProperties();
        props.setRoot(root.toString());
        storage = new LocalDiskStorage(props);
        storage.init();
    }

    // ---------------- 分片 ----------------

    @Test
    @DisplayName("分片落盘后能被列出来，且不包含未写完的中间文件")
    void saveAndListChunks() throws IOException {
        storage.saveChunk(ID, 0, in("hello"));
        storage.saveChunk(ID, 2, in("world"));

        assertEquals(List.of(0, 2), storage.listChunksOnDisk(ID));
        assertTrue(storage.chunkExists(ID, 0));
        assertFalse(storage.chunkExists(ID, 1));
        assertEquals(5, storage.chunkLength(ID, 0));
    }

    @Test
    @DisplayName("重复落盘同一分片是覆盖，不会变成两片")
    void saveChunkIsIdempotent() throws IOException {
        storage.saveChunk(ID, 0, in("first"));
        storage.saveChunk(ID, 0, in("second-longer"));

        assertEquals(List.of(0), storage.listChunksOnDisk(ID));
        assertEquals("second-longer".length(), storage.chunkLength(ID, 0));
    }

    @Test
    @DisplayName("目录里残留的 .incoming 不会被当成完整分片")
    void incomingFileIsIgnored() throws IOException {
        storage.saveChunk(ID, 0, in("ok"));
        // 手工造一个"写了一半"的中间产物，模拟进程被杀
        Path dir = storage.chunkFile(ID, 0).getParent();
        Files.writeString(dir.resolve("3.abc.part.incoming"), "half");

        assertEquals(List.of(0), storage.listChunksOnDisk(ID));
    }

    @Test
    @DisplayName("分片摘要不匹配时拒绝落盘，并保留之前正确的旧分片")
    void rejectsBadChunkWithoutOverwritingGoodChunk() throws IOException {
        byte[] good = "correct-chunk".getBytes(StandardCharsets.UTF_8);
        byte[] bad = "corrupted-bytes".getBytes(StandardCharsets.UTF_8);
        String goodHash = HashUtil.sha256Hex(good);

        LocalDiskStorage.ChunkWriteResult first =
                storage.saveChunk(ID, 0, new ByteArrayInputStream(good), goodHash, good.length);
        assertTrue(first.accepted());

        // 客户端重传同一片时带了损坏的字节：服务端复算摘要对不上
        LocalDiskStorage.ChunkWriteResult second =
                storage.saveChunk(ID, 0, new ByteArrayInputStream(bad), goodHash, bad.length);
        assertFalse(second.accepted());
        assertEquals(goodHash, first.sha256());
        assertEquals("correct-chunk", new String(Files.readAllBytes(storage.chunkFile(ID, 0)),
                StandardCharsets.UTF_8), "坏重传不能把已经正确落盘的旧分片覆盖掉");
    }

    @Test
    @DisplayName("分片大小不符时拒绝落盘，也不留 .incoming 垃圾")
    void rejectsWrongSizeChunk() throws IOException {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        LocalDiskStorage.ChunkWriteResult result = storage.saveChunk(
                ID, 0, new ByteArrayInputStream(data), HashUtil.sha256Hex(data), data.length + 1);

        assertFalse(result.accepted());
        assertFalse(storage.chunkExists(ID, 0));

        Path dir = storage.chunkFile(ID, 0).getParent();
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".incoming")));
        }
    }

    @Test
    @DisplayName("临时文件清理只删过期的 .incoming 和 .merging")
    void cleanupTemporaryOnlyRemovesStaleFiles() throws IOException {
        Path chunkDir = storage.chunkFile(ID, 0).getParent();
        Files.createDirectories(chunkDir);
        Path staleIncoming = Files.writeString(chunkDir.resolve("0.abc.part.incoming"), "half");
        Path freshIncoming = Files.writeString(chunkDir.resolve("1.def.part.incoming"), "half");

        Files.setLastModifiedTime(staleIncoming,
                FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        int deleted = storage.cleanupTemporary(Duration.ofHours(1));

        assertEquals(1, deleted);
        assertFalse(Files.exists(staleIncoming));
        assertTrue(Files.exists(freshIncoming));
    }

    @Test
    @DisplayName("孤儿目录扫描只认 UUID 形状的目录，并能按 mtime 判断是否过期")
    void listsChunkDirsAndChecksAge() throws IOException {
        storage.saveChunk(ID, 0, in("a"));
        Path orphan = storage.chunkFile(ID, 0).getParent();
        Files.createDirectories(root.resolve("chunks").resolve("not-a-uuid"));

        assertEquals(List.of(ID), storage.listChunkDirs());
        assertFalse(storage.chunkDirOlderThan(ID, Duration.ofHours(1)));

        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        assertTrue(storage.chunkDirOlderThan(ID, Duration.ofHours(1)));
        assertFalse(storage.chunkDirOlderThan(OTHER_ID, Duration.ofHours(1)),
                "不存在的目录不应被当成可删除的孤儿");
    }

    @Test
    @DisplayName("删除分片目录后，列表为空且可重复调用")
    void deleteChunks() throws IOException {
        storage.saveChunk(ID, 0, in("a"));
        storage.saveChunk(ID, 1, in("b"));

        storage.deleteChunks(ID);
        assertEquals(List.of(), storage.listChunksOnDisk(ID));

        storage.deleteChunks(ID);   // 幂等：再删一次不该抛异常
        assertEquals(List.of(), storage.listChunksOnDisk(ID));
    }

    @Test
    @DisplayName("上传中断时清理临时片，并保留原来的正确分片")
    void interruptedChunkCleansIncomingAndKeepsExisting() throws IOException {
        storage.saveChunk(ID, 0, in("good"));
        InputStream interrupted = new InputStream() {
            int remaining = 16;
            @Override
            public int read() throws IOException {
                if (remaining-- > 0) return 'x';
                throw new IOException("connection interrupted");
            }
        };
        IOException error = assertThrows(IOException.class,
                () -> storage.saveChunk(ID, 0, interrupted));
        assertEquals("connection interrupted", error.getMessage());
        assertEquals("good", Files.readString(storage.chunkFile(ID, 0)));
        assertNoIncomingFiles();
    }

    @Test
    @DisplayName("输入流关闭失败时清理临时片，不发布新分片")
    void closeFailureCleansIncoming() {
        InputStream badClose = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
        assertThrows(IOException.class, () -> storage.saveChunk(ID, 0, badClose));
        assertFalse(storage.chunkExists(ID, 0));
        assertNoIncomingFiles();
    }

    @Test
    @DisplayName("最终移动失败时也不残留本次临时片")
    void moveFailureCleansIncoming() throws IOException {
        Path target = storage.chunkFile(ID, 0);
        Files.createDirectories(target);
        Files.writeString(target.resolve("keep.txt"), "existing data");
        assertThrows(IOException.class, () -> storage.saveChunk(ID, 0, in("new")));
        assertEquals("existing data", Files.readString(target.resolve("keep.txt")));
        assertNoIncomingFiles();
    }

    private void assertNoIncomingFiles() {
        try (var paths = Files.list(storage.chunkFile(ID, 0).getParent())) {
            assertTrue(paths.noneMatch(p -> p.getFileName().toString().endsWith(".incoming")));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------- 路径安全 ----------------

    @Test
    @DisplayName("fileId 来自客户端，必须挡住目录穿越")
    void rejectsPathTraversalIds() {
        String[] evil = {
                "../../etc/passwd",
                "..\\..\\windows\\system32",
                "abc",
                "",
                "1f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f",      // 少一位
                "1f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8/../x", // 合法形状 + 穿越
        };
        for (String bad : evil) {
            assertThrows(BizException.class, () -> LocalDiskStorage.requireSafeId(bad),
                    "应该拒绝：" + bad);
        }
    }

    @Test
    @DisplayName("合法 UUID 放行")
    void acceptsUuid() {
        assertEquals(ID, LocalDiskStorage.requireSafeId(ID));
    }

    // ---------------- 成品存取 ----------------

    @Test
    @DisplayName("put 之后能读回、能统计大小；delete 之后再读报错")
    void putOpenDelete() throws IOException {
        Path src = Files.writeString(root.resolve("src.bin"), "payload");
        String rel = storage.buildRelativePath(ID);
        storage.put(rel, src);

        assertTrue(storage.exists(rel));
        assertEquals(7, storage.size(rel));
        assertEquals("payload", new String(storage.open(rel).readAllBytes(), StandardCharsets.UTF_8));

        assertTrue(storage.delete(rel));
        assertFalse(storage.exists(rel));
        assertFalse(storage.delete(rel), "删除不存在的文件应返回 false 而不是抛异常");
    }

    @Test
    @DisplayName("成品扫描只认 UUID 文件名，并能按 mtime 判断是否过宽限期")
    void listsFinishedFilesAndChecksAge() throws IOException {
        Path src = Files.writeString(root.resolve("src.bin"), "payload");
        String rel = storage.buildRelativePath(ID);
        storage.put(rel, src);

        // 用当前日期的分片目录，别写死日期（跨过零点后目录不存在，测试会假红）
        String shard = rel.substring(0, rel.lastIndexOf('/'));
        Path ignored = root.resolve("files").resolve(shard).resolve("not-a-uuid");
        Files.writeString(ignored, "manual");

        assertEquals(List.of(rel), storage.listFilePaths());
        assertFalse(storage.fileOlderThan(rel, Duration.ofHours(1)));

        Files.setLastModifiedTime(root.resolve("files").resolve(rel),
                FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        assertTrue(storage.fileOlderThan(rel, Duration.ofHours(1)));
        assertFalse(storage.fileOlderThan(shard + "/" + OTHER_ID, Duration.ofHours(1)),
                "不存在的成品不应被当成可删除的孤儿");
    }

    @Test
    @DisplayName("成品路径按日期分目录，不会全挤在一个目录里")
    void relativePathIsDateSharded() {
        String rel = storage.buildRelativePath(ID);
        assertTrue(rel.endsWith("/" + ID), rel);
        assertEquals(4, rel.split("/").length, rel);   // yyyy/MM/dd/{id}
    }

    @Test
    @DisplayName("openRange 直接定位到偏移，不会把前面的字节读出来")
    void openRangeReadsFromOffset() throws IOException {
        Path src = Files.writeString(root.resolve("range.bin"), "0123456789");
        String rel = storage.buildRelativePath(ID);
        storage.put(rel, src);

        try (InputStream in = storage.openRange(rel, 4, 3)) {
            assertEquals("456", new String(in.readNBytes(3), StandardCharsets.UTF_8));
        }
        try (InputStream in = storage.openRange(rel, 0, 10)) {
            assertEquals("0123456789", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("穿越路径写不进来")
    void rejectsTraversalOnPut() {
        Path src = root.resolve("x.bin");
        assertThrows(BizException.class, () -> storage.put("../../escaped.bin", src));
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
