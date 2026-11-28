package com.yuzhuo.filehub.service;

import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.common.FileLockRegistry;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.dto.CheckResponse;
import com.yuzhuo.filehub.dto.FileVO;
import com.yuzhuo.filehub.dto.InitUploadRequest;
import com.yuzhuo.filehub.entity.FileChunk;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.mapper.FileChunkMapper;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.storage.LocalDiskStorage;
import com.yuzhuo.filehub.storage.LocalDiskStorage.ChunkWriteResult;
import com.yuzhuo.filehub.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    static final String ID = "1f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";
    static final String OTHER_ID = "2f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";
    static final UserPrincipal USER = new UserPrincipal(7L, "alice", "USER");
    static final UserPrincipal OTHER_USER = new UserPrincipal(8L, "bob", "USER");
    static final UserPrincipal ADMIN = new UserPrincipal(1L, "admin", "ADMIN");

    @Mock
    FileInfoMapper fileInfoMapper;
    @Mock
    FileChunkMapper fileChunkMapper;
    @Mock
    LocalDiskStorage storage;

    @TempDir
    Path tempDir;

    FileStorageService service;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setChunkSize(5);
        props.setUploadExpireMinutes(60);
        service = new FileStorageService(fileInfoMapper, fileChunkMapper, storage, props,
                new FileLockRegistry(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    @DisplayName("落盘后任务被清理：回滚刚写的分片并返回 10005")
    void saveChunkCleansUpWhenTaskDisappears() throws Exception {
        when(fileInfoMapper.selectOne(any())).thenReturn(uploadingInfo()).thenReturn(null);
        when(storage.saveChunk(eq(ID), eq(0), any(InputStream.class), isNull(), eq(5L)))
                .thenReturn(new ChunkWriteResult(5, "b".repeat(64), true));
        when(fileInfoMapper.touchUploading(ID)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () ->
                service.saveChunk(ID, 0, in("hello"), null, USER));

        assertEquals(10005, ex.getCode());
        verify(storage).deleteChunk(ID, 0);
        verify(fileChunkMapper).deleteChunk(ID, 0);
    }

    @Test
    @DisplayName("分片到达时文件已合并完成：丢弃迟到分片但不报错")
    void lateChunkIsDiscardedWhenAlreadyDone() throws Exception {
        FileInfo done = uploadingInfo();
        done.setStatus(FileInfo.STATUS_DONE);
        when(fileInfoMapper.selectOne(any())).thenReturn(uploadingInfo()).thenReturn(done);
        when(storage.saveChunk(eq(ID), eq(0), any(InputStream.class), isNull(), eq(5L)))
                .thenReturn(new ChunkWriteResult(5, "b".repeat(64), true));
        when(fileInfoMapper.touchUploading(ID)).thenReturn(0);

        assertDoesNotThrow(() -> service.saveChunk(ID, 0, in("hello"), null, USER));

        verify(storage).deleteChunk(ID, 0);
        verify(fileChunkMapper).deleteChunk(ID, 0);
    }

    @Test
    @DisplayName("清理只处理条件删除成功的任务：查询后刚被分片刷新的任务不删")
    void cleanupSkipsTaskRefreshedAfterQuery() {
        FileInfo stale = uploadingInfo();
        stale.setId(9L);
        when(fileInfoMapper.findExpiredUploading(any(LocalDateTime.class))).thenReturn(List.of(stale));
        when(fileInfoMapper.deleteExpiredById(eq(9L), any(LocalDateTime.class))).thenReturn(0);
        when(fileInfoMapper.findUploadingFileIds()).thenReturn(List.of());
        when(storage.listChunkDirs()).thenReturn(List.of());
        when(storage.cleanupTemporary(any(Duration.class))).thenReturn(0);

        service.cleanupExpiredUploads();

        verify(fileInfoMapper).deleteExpiredById(eq(9L), any(LocalDateTime.class));
        verify(storage, never()).deleteChunks(anyString());
        verify(fileChunkMapper, never()).deleteByFileId(anyString());
    }

    @Test
    @DisplayName("孤儿分片目录：无对应任务且足够旧才回收")
    void cleanupRemovesOnlyStaleOrphanChunkDirs() {
        when(fileInfoMapper.findExpiredUploading(any(LocalDateTime.class))).thenReturn(List.of());
        when(fileInfoMapper.findUploadingFileIds()).thenReturn(List.of());
        when(storage.listChunkDirs()).thenReturn(List.of(OTHER_ID));
        when(storage.chunkDirOlderThan(eq(OTHER_ID), any(Duration.class))).thenReturn(true);
        when(fileInfoMapper.selectOne(any())).thenReturn(null);
        when(storage.cleanupTemporary(any(Duration.class))).thenReturn(0);

        service.cleanupExpiredUploads();

        verify(storage).deleteChunks(OTHER_ID);
        verify(fileChunkMapper).deleteByFileId(OTHER_ID);
    }

    @Test
    @DisplayName("秒传预检以磁盘为准：物理文件缺失时按未命中处理")
    void checkMissesWhenPhysicalFileIsGone() {
        FileInfo done = doneInfo();
        when(fileInfoMapper.findDoneListByOwnerAndHashAndSize("a".repeat(64), 5, 7L, false))
                .thenReturn(List.of(done));
        when(storage.exists(done.getStoragePath())).thenReturn(false);

        CheckResponse resp = service.check("a".repeat(64), 5, USER);

        assertFalse(resp.exists());
        assertNull(resp.fileId());
    }

    @Test
    @DisplayName("秒传预检：跳过坏记录，命中另一条物理文件完好的记录")
    void checkSkipsBrokenCandidateAndFindsUsableOne() {
        FileInfo broken = doneInfo();
        FileInfo good = doneInfo();
        good.setFileId(OTHER_ID);
        good.setStoragePath("2026/11/18/" + OTHER_ID);

        when(fileInfoMapper.findDoneListByOwnerAndHashAndSize("a".repeat(64), 5, 7L, false))
                .thenReturn(List.of(broken, good));
        when(storage.exists(broken.getStoragePath())).thenReturn(false);
        when(storage.exists(good.getStoragePath())).thenReturn(true);
        when(storage.size(good.getStoragePath())).thenReturn(5L);

        CheckResponse resp = service.check("a".repeat(64), 5, USER);

        assertTrue(resp.exists());
        assertEquals(OTHER_ID, resp.fileId());
    }

    @Test
    @DisplayName("合并成功后清掉 file_chunk 元数据，避免分片行永久堆积")
    void completeRemovesChunkMetadata() throws Exception {
        String hash = HashUtil.sha256Hex("hello".getBytes(StandardCharsets.UTF_8));
        FileInfo uploading = uploadingInfo();
        uploading.setFileHash(hash);
        FileInfo done = doneInfo();
        done.setFileHash(hash);

        Path chunk = Files.writeString(tempDir.resolve("0.part"), "hello");
        Path merged = Files.createTempFile(tempDir, "merge", ".tmp");

        when(fileInfoMapper.selectOne(any())).thenReturn(uploading).thenReturn(done);
        when(storage.listChunksOnDisk(ID)).thenReturn(List.of(0));
        FileChunk meta = new FileChunk();
        meta.setChunkIndex(0);
        meta.setChunkHash(hash);
        when(fileChunkMapper.listChunkMeta(ID)).thenReturn(List.of(meta));
        when(storage.createMergeTmpFile(ID)).thenReturn(merged);
        when(storage.chunkFile(ID, 0)).thenReturn(chunk);
        when(fileInfoMapper.findDoneListByHashAndSize(hash, 5)).thenReturn(List.of());
        when(storage.buildRelativePath(ID)).thenReturn("2026/11/18/" + ID);
        when(fileInfoMapper.markDone(eq(ID), anyString())).thenReturn(1);

        FileVO vo = service.complete(ID, USER);

        assertEquals(ID, vo.fileId());
        verify(fileChunkMapper).deleteByFileId(ID);
        verify(storage).deleteChunks(ID);
    }

    @Test
    @DisplayName("孤儿成品回收：有引用的留着，无引用且过宽限期的才删")
    void cleanupRemovesOnlyUnreferencedOldFiles() {
        String orphan = "2026/11/18/" + OTHER_ID;
        String keep = "2026/11/18/" + ID;
        when(fileInfoMapper.findAllStoragePaths()).thenReturn(List.of(keep));
        when(storage.listFilePaths()).thenReturn(List.of(orphan, keep));
        when(storage.fileOlderThan(eq(orphan), any(Duration.class))).thenReturn(true);
        when(fileInfoMapper.countByStoragePath(orphan)).thenReturn(0);
        when(storage.delete(orphan)).thenReturn(true);

        service.cleanupExpiredUploads();

        verify(storage).delete(orphan);
        verify(storage, never()).delete(keep);
    }

    @Test
    @DisplayName("用户隔离：别人的 fileId 和非 NULL 历史数据对普通用户一律不可见")
    void requireInfoRejectsOtherOwnerAndLegacyRows() {
        FileInfo other = doneInfo();
        other.setOwnerId(OTHER_USER.id());
        when(fileInfoMapper.selectOne(any())).thenReturn(other);

        BizException ex = assertThrows(BizException.class, () -> service.requireInfo(ID, USER));
        assertEquals(10005, ex.getCode());

        FileInfo legacy = doneInfo();
        legacy.setOwnerId(null);
        when(fileInfoMapper.selectOne(any())).thenReturn(legacy);
        ex = assertThrows(BizException.class, () -> service.requireInfo(ID, USER));
        assertEquals(10005, ex.getCode());
    }

    @Test
    @DisplayName("管理员可以访问历史无主数据")
    void adminCanAccessLegacyRows() {
        FileInfo legacy = doneInfo();
        legacy.setOwnerId(null);
        when(fileInfoMapper.selectOne(any())).thenReturn(legacy);

        assertEquals(ID, service.requireInfo(ID, ADMIN).getFileId());
    }

    @Test
    @DisplayName("秒传预检按 owner 查询：管理员包含 owner_id IS NULL")
    void checkUsesAdminScopeForLegacy() {
        when(fileInfoMapper.findDoneListByOwnerAndHashAndSize("a".repeat(64), 5, 1L, true))
                .thenReturn(List.of());

        service.check("a".repeat(64), 5, ADMIN);

        verify(fileInfoMapper).findDoneListByOwnerAndHashAndSize("a".repeat(64), 5, 1L, true);
    }

    @Test
    @DisplayName("主动删除未完成上传时同时清除分片元数据和磁盘目录")
    void deleteUploadingClearsChunkMetadata() {
        when(fileInfoMapper.selectOne(any())).thenReturn(uploadingInfo());

        service.delete(ID, USER);

        var order = inOrder(fileInfoMapper, fileChunkMapper, storage);
        order.verify(fileInfoMapper).deleteById(1L);
        order.verify(fileChunkMapper).deleteByFileId(ID);
        order.verify(storage).deleteChunks(ID);
    }

    @Test
    @DisplayName("分片元数据删除失败时保留目录，让孤儿回收可以重试")
    void deleteMetadataFailureKeepsDirectoryForCleanup() {
        when(fileInfoMapper.selectOne(any())).thenReturn(uploadingInfo());
        doThrow(new IllegalStateException("database unavailable"))
                .when(fileChunkMapper).deleteByFileId(ID);

        assertThrows(IllegalStateException.class, () -> service.delete(ID, USER));

        verify(storage, never()).deleteChunks(ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/folder/hello.bin", "../hello.bin", "folder/../hello.bin",
            "folder/./hello.bin", "folder//hello.bin", "folder/hello.bin/", "folder/other.bin",
            "C:/folder/hello.bin", "C:\\folder\\hello.bin", "folder/\u0000hello.bin",
            "folder/\nhello.bin"})
    @DisplayName("文件夹路径拒绝绝对路径、目录穿越、控制字符和文件名不匹配")
    void rejectsInvalidRelativePaths(String relativePath) {
        BizException ex = assertThrows(BizException.class, () -> service.init(
                new InitUploadRequest("hello.bin", 5L, "a".repeat(64), relativePath), USER));

        assertEquals(10001, ex.getCode());
        verifyNoInteractions(fileInfoMapper, fileChunkMapper, storage);
    }

    @Test
    @DisplayName("文件夹相对路径最多 4096 字符，拒绝过长路径")
    void rejectsOverlongRelativePath() {
        BizException ex = assertThrows(BizException.class, () -> service.init(
                new InitUploadRequest("hello.bin", 5L, "a".repeat(64),
                        "dir/".repeat(1024) + "hello.bin"), USER));

        assertEquals(10001, ex.getCode());
        verifyNoInteractions(fileInfoMapper, fileChunkMapper, storage);
    }

    @Test
    @DisplayName("即使数据库忽略大小写，续传也必须精确匹配相对路径和文件名")
    void resumeUsesExactPathAndFileNameEvenWithCaseInsensitiveDatabase() {
        FileInfo wrongCasePath = uploadingInfo();
        wrongCasePath.setRelativePath("Folder/hello.bin");
        FileInfo wrongCaseName = uploadingInfo();
        wrongCaseName.setFileName("HELLO.bin");
        wrongCaseName.setRelativePath("folder/hello.bin");
        FileInfo exact = uploadingInfo();
        exact.setFileId(OTHER_ID);
        exact.setRelativePath("folder/hello.bin");
        when(fileInfoMapper.selectList(any())).thenReturn(List.of(wrongCasePath, wrongCaseName, exact));
        when(fileInfoMapper.touchUploading(OTHER_ID)).thenReturn(1);

        var resumed = service.init(
                new InitUploadRequest("hello.bin", 5L, "a".repeat(64), "folder/hello.bin"), USER);

        assertEquals(OTHER_ID, resumed.fileId());
        verify(fileInfoMapper, never()).touchUploading(ID);
        verify(fileInfoMapper, never()).insert(any(FileInfo.class));
    }

    @Test
    @DisplayName("大小写不同的目录没有精确匹配时新建独立上传记录")
    void differentCasePathCreatesSeparateUpload() {
        FileInfo wrongPath = uploadingInfo();
        wrongPath.setRelativePath("Folder/hello.bin");
        when(fileInfoMapper.selectList(any())).thenReturn(List.of(wrongPath));

        service.init(new InitUploadRequest("hello.bin", 5L, "a".repeat(64),
                "folder/hello.bin"), USER);

        ArgumentCaptor<FileInfo> inserted = ArgumentCaptor.forClass(FileInfo.class);
        verify(fileInfoMapper).insert(inserted.capture());
        assertEquals("folder/hello.bin", inserted.getValue().getRelativePath());
        assertEquals("hello.bin", inserted.getValue().getFileName());
        verify(fileInfoMapper, never()).touchUploading(anyString());
    }

    @Test
    @DisplayName("安全：仅知道他人私有文件的 SHA-256 与大小不能跨账号免传秒传")
    void initDoesNotInstantUploadUsingOtherUsersFile() {
        String hash = "b".repeat(64);
        when(fileInfoMapper.findDoneListByOwnerAndHashAndSize(hash, 5L, OTHER_USER.id(), false))
                .thenReturn(List.of());
        when(fileInfoMapper.selectList(any())).thenReturn(List.of());

        var resp = service.init(new InitUploadRequest("secret.bin", 5L, hash), OTHER_USER);

        assertFalse(resp.instant());
        verify(fileInfoMapper).findDoneListByOwnerAndHashAndSize(hash, 5L, OTHER_USER.id(), false);
        verify(fileInfoMapper, never()).findDoneListByHashAndSize(hash, 5L);
    }

    @Test
    @DisplayName("清空回收站：并发恢复的文件（status 已不是 TRASH）不会被误删")
    void emptyTrashSkipsConcurrentlyRestoredFile() {
        FileInfo staleSnapshot = doneInfo();
        staleSnapshot.setStatus(FileInfo.STATUS_TRASH);

        FileInfo restoredNow = doneInfo();
        restoredNow.setStatus(FileInfo.STATUS_DONE);

        when(fileInfoMapper.selectList(any())).thenReturn(List.of(staleSnapshot));
        when(fileInfoMapper.selectOne(any())).thenReturn(restoredNow);

        int count = service.emptyTrash(USER);

        assertEquals(0, count);
        verify(fileInfoMapper, never()).deleteById(any(java.io.Serializable.class));
        verify(fileInfoMapper, never()).deleteTrashById(any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("清空回收站：仅当条件删除成功删除 TRASH 行时才清理物理引用")
    void emptyTrashDeletesOnlyWhenTrashRowDeleted() {
        FileInfo trashed = doneInfo();
        trashed.setStatus(FileInfo.STATUS_TRASH);

        when(fileInfoMapper.selectList(any())).thenReturn(List.of(trashed));
        when(fileInfoMapper.selectOne(any())).thenReturn(trashed);
        when(fileInfoMapper.deleteTrashById(1L)).thenReturn(1);
        when(fileInfoMapper.countByStoragePath(trashed.getStoragePath())).thenReturn(0);

        int count = service.emptyTrash(USER);

        assertEquals(1, count);
        verify(fileInfoMapper).deleteTrashById(1L);
        verify(storage).delete(trashed.getStoragePath());
    }

    private static FileInfo doneInfo() {
        FileInfo info = uploadingInfo();
        info.setStatus(FileInfo.STATUS_DONE);
        info.setStoragePath("2026/11/18/" + ID);
        return info;
    }

    private static FileInfo uploadingInfo() {
        FileInfo info = new FileInfo();
        info.setId(1L);
        info.setFileId(ID);
        info.setFileName("hello.bin");
        info.setRelativePath("");
        info.setFileSize(5L);
        info.setFileHash("a".repeat(64));
        info.setChunkSize(5);
        info.setChunkTotal(1);
        info.setStatus(FileInfo.STATUS_UPLOADING);
        info.setOwnerId(USER.id());
        info.setCreateTime(LocalDateTime.now());
        info.setUpdateTime(LocalDateTime.now());
        return info;
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
