package com.yuzhuo.filehub.storage;

import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3StorageBackendTest {

    @Mock
    S3Client s3;

    @TempDir
    Path tempDir;

    StorageProperties.S3 config;
    S3StorageBackend backend;

    @BeforeEach
    void setUp() {
        config = new StorageProperties.S3();
        config.setBucket("test-bucket");
        config.setKeyPrefix("files/");
        config.setMultipartThresholdBytes(5L * 1024 * 1024);
        config.setMultipartPartBytes(5L * 1024 * 1024);
        backend = new S3StorageBackend(s3, config);
    }

    @Test
    @DisplayName("桶不存在且开启自动建桶时自动创建桶")
    void ensureBucketCreatesWhenMissing() {
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.builder().message("missing").build());

        backend.ensureBucket();

        ArgumentCaptor<CreateBucketRequest> req = ArgumentCaptor.forClass(CreateBucketRequest.class);
        verify(s3).createBucket(req.capture());
        assertEquals("test-bucket", req.getValue().bucket());
    }

    @Test
    @DisplayName("小文件直接单次 PutObject 并在成功后删除本地暂存文件")
    void putSmallFileUploadsAndDeletesSource() throws IOException {
        Path source = tempDir.resolve("merged.bin");
        Files.writeString(source, "hello-s3", StandardCharsets.UTF_8);

        backend.put("2026/11/26/abc", source);

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(req.capture(), any(RequestBody.class));
        assertEquals("test-bucket", req.getValue().bucket());
        assertEquals("files/2026/11/26/abc", req.getValue().key());
        assertEquals(8L, req.getValue().contentLength());
        assertFalse(Files.exists(source));
    }

    @Test
    @DisplayName("超过阈值的大文件走 Multipart Upload 分片上传并完成合并")
    void putLargeFileUsesMultipartUpload() throws IOException {
        Path source = tempDir.resolve("big.bin");
        // 6MB > 5MB threshold
        byte[] data = new byte[6 * 1024 * 1024];
        data[0] = 1;
        data[data.length - 1] = 2;
        Files.write(source, data);

        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("up-1").build());
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("etag-1").build())
                .thenReturn(UploadPartResponse.builder().eTag("etag-2").build());

        backend.put("2026/11/26/big", source);

        verify(s3, times(2)).uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
        ArgumentCaptor<CompleteMultipartUploadRequest> complete =
                ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(s3).completeMultipartUpload(complete.capture());
        assertEquals("up-1", complete.getValue().uploadId());
        assertEquals(2, complete.getValue().multipartUpload().parts().size());
        assertFalse(Files.exists(source));
    }

    @Test
    @DisplayName("分片上传中途失败时触发 abortMultipartUpload 且保留本地文件不误删")
    void putMultipartFailureAbortsUpload() throws IOException {
        Path source = tempDir.resolve("fail.bin");
        Files.write(source, new byte[6 * 1024 * 1024]);

        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("up-err").build());
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("network reset"));

        assertThrows(IOException.class, () -> backend.put("2026/11/26/fail", source));

        ArgumentCaptor<AbortMultipartUploadRequest> abort =
                ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(s3).abortMultipartUpload(abort.capture());
        assertEquals("up-err", abort.getValue().uploadId());
        assertTrue(Files.exists(source));
    }

    @Test
    @DisplayName("openRange 传入精确 HTTP Range 字节区间")
    void openRangePassesExactRangeHeader() throws IOException {
        byte[] slice = "world".getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> stream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(slice)));
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream);

        try (var in = backend.openRange("2026/11/26/a", 6, 5)) {
            assertArrayEquals(slice, in.readAllBytes());
        }

        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(req.capture());
        assertEquals("bytes=6-10", req.getValue().range());
    }

    @Test
    @DisplayName("exists / size / delete 遵循 StorageBackend 语义")
    void existsSizeAndDeleteContracts() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(128L)
                        .lastModified(Instant.now().minus(Duration.ofDays(10))).build());

        assertTrue(backend.exists("2026/11/26/a"));
        assertEquals(128L, backend.size("2026/11/26/a"));
        assertTrue(backend.fileOlderThan("2026/11/26/a", Duration.ofDays(7)));
        assertTrue(backend.delete("2026/11/26/a"));
        verify(s3).deleteObject(any(DeleteObjectRequest.class));

        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        assertFalse(backend.exists("2026/11/26/missing"));
        assertEquals(-1L, backend.size("2026/11/26/missing"));
        assertFalse(backend.delete("2026/11/26/missing"));
        verify(s3, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("listFilePaths 过滤非 UUID 对象，拒绝目录穿越路径")
    void listAndRejectPathTraversal() {
        String id = UUID.randomUUID().toString();
        ListObjectsV2Response page = ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder().key("files/2026/11/26/" + id).build(),
                        S3Object.builder().key("files/manual-readme.txt").build())
                .build();
        ListObjectsV2Iterable iterable = mock(ListObjectsV2Iterable.class);
        when(iterable.iterator()).thenReturn(List.of(page).iterator());
        when(s3.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenReturn(iterable);

        List<String> paths = backend.listFilePaths();
        assertEquals(List.of("2026/11/26/" + id), paths);

        assertThrows(BizException.class, () -> backend.exists("../secret"));
        assertThrows(BizException.class, () -> backend.exists(""));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}
