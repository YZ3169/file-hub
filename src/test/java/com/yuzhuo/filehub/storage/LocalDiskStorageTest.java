package com.yuzhuo.filehub.storage;

import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Files.writeString(dir.resolve("3.part.incoming"), "half");

        assertEquals(List.of(0), storage.listChunksOnDisk(ID));
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
    @DisplayName("成品路径按日期分目录，不会全挤在一个目录里")
    void relativePathIsDateSharded() {
        String rel = storage.buildRelativePath(ID);
        assertTrue(rel.endsWith("/" + ID), rel);
        assertEquals(4, rel.split("/").length, rel);   // yyyy/MM/dd/{id}
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
