package com.yuzhuo.filehub.service;

import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.common.FileLockRegistry;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.mapper.FileChunkMapper;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.storage.LocalDiskStorage;
import com.yuzhuo.filehub.storage.LocalDiskStorage.ChunkWriteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    static final String ID = "1f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";
    static final String OTHER_ID = "2f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";

    @Mock
    FileInfoMapper fileInfoMapper;
    @Mock
    FileChunkMapper fileChunkMapper;
    @Mock
    LocalDiskStorage storage;

    FileStorageService service;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setChunkSize(5);
        props.setUploadExpireMinutes(60);
        service = new FileStorageService(fileInfoMapper, fileChunkMapper, storage, props, new FileLockRegistry());
    }

    @Test
    @DisplayName("落盘后任务被清理：回滚刚写的分片并返回 10005")
    void saveChunkCleansUpWhenTaskDisappears() throws Exception {
        when(fileInfoMapper.selectOne(any())).thenReturn(uploadingInfo()).thenReturn(null);
        when(storage.saveChunk(eq(ID), eq(0), any(InputStream.class), isNull(), eq(5L)))
                .thenReturn(new ChunkWriteResult(5, "b".repeat(64), true));
        when(fileInfoMapper.touchUploading(ID)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () ->
                service.saveChunk(ID, 0, in("hello"), null));

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

        assertDoesNotThrow(() -> service.saveChunk(ID, 0, in("hello"), null));

        verify(storage).deleteChunk(ID, 0);
        verify(fileChunkMapper).deleteChunk(ID, 0);
    }

    @Test
    @DisplayName("清理只处理条件删除成功的任务：查询后刚被分片刷新的任务不删")
    void cleanupSkipsTaskRefreshedAfterQuery() {
        FileInfo stale = uploadingInfo();
        stale.setId(9L);
        when(fileInfoMapper.findExpiredUploading(60)).thenReturn(List.of(stale));
        when(fileInfoMapper.deleteExpiredById(9L, 60)).thenReturn(0);
        when(fileInfoMapper.findUploadingFileIds()).thenReturn(List.of());
        when(storage.listChunkDirs()).thenReturn(List.of());
        when(storage.cleanupTemporary(any(Duration.class))).thenReturn(0);

        service.cleanupExpiredUploads();

        verify(fileInfoMapper).deleteExpiredById(9L, 60);
        verify(storage, never()).deleteChunks(anyString());
        verify(fileChunkMapper, never()).deleteByFileId(anyString());
    }

    @Test
    @DisplayName("孤儿分片目录：无对应任务且足够旧才回收")
    void cleanupRemovesOnlyStaleOrphanChunkDirs() {
        when(fileInfoMapper.findExpiredUploading(60)).thenReturn(List.of());
        when(fileInfoMapper.findUploadingFileIds()).thenReturn(List.of());
        when(storage.listChunkDirs()).thenReturn(List.of(OTHER_ID));
        when(storage.chunkDirOlderThan(eq(OTHER_ID), any(Duration.class))).thenReturn(true);
        when(fileInfoMapper.selectOne(any())).thenReturn(null);
        when(storage.cleanupTemporary(any(Duration.class))).thenReturn(0);

        service.cleanupExpiredUploads();

        verify(storage).deleteChunks(OTHER_ID);
        verify(fileChunkMapper).deleteByFileId(OTHER_ID);
    }

    private static FileInfo uploadingInfo() {
        FileInfo info = new FileInfo();
        info.setId(1L);
        info.setFileId(ID);
        info.setFileName("hello.bin");
        info.setFileSize(5L);
        info.setFileHash("a".repeat(64));
        info.setChunkSize(5);
        info.setChunkTotal(1);
        info.setStatus(FileInfo.STATUS_UPLOADING);
        info.setCreateTime(LocalDateTime.now());
        info.setUpdateTime(LocalDateTime.now());
        return info;
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
