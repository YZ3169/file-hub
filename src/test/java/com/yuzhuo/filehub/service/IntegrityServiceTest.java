package com.yuzhuo.filehub.service;

import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.dto.IntegrityRepairRequest;
import com.yuzhuo.filehub.dto.IntegrityRepairResponse;
import com.yuzhuo.filehub.dto.IntegrityScanResponse;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.storage.StorageBackend;
import com.yuzhuo.filehub.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrityServiceTest {

    static final String ID1 = "1f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";
    static final String ID2 = "2f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";
    static final String ID3 = "3f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";
    static final String P1 = "2026/11/18/" + ID1;
    static final String P2 = "2026/11/18/" + ID2;
    static final String P3 = "2026/11/18/" + ID3;
    static final UserPrincipal ADMIN = new UserPrincipal(1L, "admin", "ADMIN");
    static final String HELLO_HASH = HashUtil.sha256Hex("hello".getBytes(StandardCharsets.UTF_8));

    @Mock
    FileInfoMapper fileInfoMapper;
    @Mock
    StorageBackend storage;
    @Mock
    FileStorageService fileStorageService;

    IntegrityService service;

    @BeforeEach
    void setUp() {
        service = new IntegrityService(fileInfoMapper, storage, fileStorageService);
    }

    @Test
    @DisplayName("浅扫描区分缺失/大小不符/健康，同一物理文件只 stat 一次")
    void scanFindsMissingAndWrongSize() throws IOException {
        FileInfo missing = done(ID1, "missing.txt", 10, "aa".repeat(32), P1);
        FileInfo wrongSize = done(ID2, "wrong-size.txt", 10, "bb".repeat(32), P2);
        FileInfo healthy = done(ID3, "healthy.txt", 5, HELLO_HASH, P3);

        when(fileInfoMapper.selectList(any())).thenReturn(List.of(missing, wrongSize, healthy));
        when(fileInfoMapper.countDone()).thenReturn(3L);
        when(storage.exists(P1)).thenReturn(false);
        when(storage.exists(P2)).thenReturn(true);
        when(storage.size(P2)).thenReturn(7L);
        when(storage.exists(P3)).thenReturn(true);
        when(storage.size(P3)).thenReturn(5L);

        IntegrityScanResponse resp = service.scan(false, 100);

        assertEquals(3, resp.checked());
        assertEquals(2, resp.brokenCount());
        assertFalse(resp.truncated());
        assertTrue(resp.items().stream().anyMatch(i -> i.fileId().equals(ID1)
                && i.reason().equals("物理文件不存在")));
        assertTrue(resp.items().stream().anyMatch(i -> i.fileId().equals(ID2)
                && i.reason().contains("大小不符")));
        verify(storage, never()).open(any());
    }

    @Test
    @DisplayName("秒传共享同一物理文件时，多条记录都报损坏，但只读一次盘")
    void scanDedupesPhysicalReads() throws IOException {
        // 同一个 storage_path，两条逻辑记录
        FileInfo a = done(ID1, "a.bin", 5, HELLO_HASH, P1);
        FileInfo b = done(ID2, "b.bin", 5, HELLO_HASH, P1);

        when(fileInfoMapper.selectList(any())).thenReturn(List.of(a, b));
        when(fileInfoMapper.countDone()).thenReturn(2L);
        when(storage.exists(P1)).thenReturn(true);
        when(storage.size(P1)).thenReturn(5L);
        when(storage.open(P1)).thenAnswer(inv ->
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        IntegrityScanResponse resp = service.scan(true, 100);

        assertEquals(0, resp.brokenCount());
        verify(storage, times(1)).open(P1);
    }

    @Test
    @DisplayName("深扫描发现大小相同但内容被改坏的文件")
    void deepScanDetectsHashMismatch() throws IOException {
        FileInfo corrupt = done(ID1, "corrupt.bin", 5, HELLO_HASH, P1);

        when(fileInfoMapper.selectList(any())).thenReturn(List.of(corrupt));
        when(fileInfoMapper.countDone()).thenReturn(1L);
        when(storage.exists(P1)).thenReturn(true);
        when(storage.size(P1)).thenReturn(5L);
        when(storage.open(P1)).thenAnswer(inv ->
                new ByteArrayInputStream("HELLO".getBytes(StandardCharsets.UTF_8)));

        IntegrityScanResponse resp = service.scan(true, 100);

        assertEquals(1, resp.brokenCount());
        assertEquals("内容哈希不一致", resp.items().get(0).reason());
    }

    @Test
    @DisplayName("扫描被 limit 截断时 truncated=true")
    void scanReportsTruncation() {
        when(fileInfoMapper.selectList(any())).thenReturn(List.of());
        when(fileInfoMapper.countDone()).thenReturn(500L);

        IntegrityScanResponse resp = service.scan(false, 100);

        assertEquals(0, resp.checked());
        assertTrue(resp.truncated());
    }

    @Test
    @DisplayName("修复只删重新确认损坏的记录：完好/不存在/上传中都不动")
    void repairDeletesOnlyBrokenRecords() throws IOException {
        FileInfo broken = done(ID1, "missing.bin", 5, HELLO_HASH, P1);
        FileInfo healthy = done(ID2, "healthy.bin", 5, HELLO_HASH, P2);
        FileInfo uploading = new FileInfo();
        uploading.setFileId(ID3);
        uploading.setStatus(FileInfo.STATUS_UPLOADING);

        when(fileInfoMapper.selectOne(any())).thenReturn(broken, healthy, uploading);
        when(storage.exists(P1)).thenReturn(false);
        when(storage.exists(P2)).thenReturn(true);
        when(storage.size(P2)).thenReturn(5L);
        when(storage.open(P2)).thenAnswer(inv ->
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        IntegrityRepairResponse resp = service.repair(
                new IntegrityRepairRequest(List.of(ID1, ID2, ID3)), ADMIN);

        assertEquals(1, resp.repaired());
        assertEquals(2, resp.failed());
        verify(fileStorageService, times(1)).delete(ID1, ADMIN);
        verify(fileStorageService, never()).delete(eq(ID2), any());
        verify(fileStorageService, never()).delete(eq(ID3), any());

        List<String> repairedIds = resp.items().stream()
                .filter(IntegrityRepairResponse.Item::repaired)
                .map(IntegrityRepairResponse.Item::fileId)
                .toList();
        assertEquals(List.of(ID1), repairedIds);
    }

    private static FileInfo done(String fileId, String name, long size, String hash, String path) {
        FileInfo info = new FileInfo();
        info.setFileId(fileId);
        info.setFileName(name);
        info.setFileSize(size);
        info.setFileHash(hash);
        info.setStoragePath(path);
        info.setStatus(FileInfo.STATUS_DONE);
        info.setOwnerId(7L);
        return info;
    }
}
