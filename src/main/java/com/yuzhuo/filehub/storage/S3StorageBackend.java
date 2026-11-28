package com.yuzhuo.filehub.storage;

import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * S3 / MinIO / 兼容对象存储的 {@link StorageBackend} 实现。
 *
 * <p>当 {@code file-hub.storage.type=s3} 时启用并标记为 {@code @Primary}，
 * 业务层（{@code FileStorageService}、{@code FileController}、{@code IntegrityService}）
 * 不做任何代码改动，成品直接写入对象存储；分片上传的临时目录仍在本地磁盘完成合并校验后再上传。</p>
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "file-hub.storage.type", havingValue = "s3")
public class S3StorageBackend implements StorageBackend {

    private static final long MIN_MULTIPART_PART_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PART_BUFFER_BYTES = 64L * 1024 * 1024;
    private static final Pattern SAFE_UUID_NAME = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final S3Client s3;
    private final StorageProperties.S3 config;
    private final String keyPrefix;
    private final boolean ownsClient;

    @Autowired
    public S3StorageBackend(StorageProperties props) {
        this.config = props.getStorage().getS3();
        this.keyPrefix = normalizePrefix(this.config.getKeyPrefix());
        this.s3 = buildClient(this.config);
        this.ownsClient = true;
    }

    /** 供单元测试注入模拟的 S3Client */
    public S3StorageBackend(S3Client s3, StorageProperties.S3 config) {
        this.s3 = s3;
        this.config = config;
        this.keyPrefix = normalizePrefix(config.getKeyPrefix());
        this.ownsClient = false;
    }

    @PostConstruct
    public void init() {
        ensureBucket();
        log.info("S3 存储后端就绪：bucket={} prefix={}", config.getBucket(), keyPrefix);
    }

    @PreDestroy
    public void shutdown() {
        if (ownsClient && s3 != null) {
            s3.close();
        }
    }

    private static S3Client buildClient(StorageProperties.S3 cfg) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(cfg.getRegion() == null || cfg.getRegion().isBlank()
                        ? "us-east-1" : cfg.getRegion().trim()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(cfg.isPathStyleAccess())
                        .build());

        if (cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.getEndpoint().trim()));
        }
        if (cfg.getAccessKey() != null && !cfg.getAccessKey().isBlank()
                && cfg.getSecretKey() != null && !cfg.getSecretKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.getAccessKey().trim(), cfg.getSecretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(config.getBucket()).build());
        } catch (NoSuchBucketException e) {
            createBucketIfEnabled(e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucketIfEnabled(e);
            } else {
                throw e;
            }
        }
    }

    private void createBucketIfEnabled(RuntimeException original) {
        if (!config.isAutoCreateBucket()) {
            throw original;
        }
        s3.createBucket(CreateBucketRequest.builder().bucket(config.getBucket()).build());
        log.info("自动创建 S3 存储桶：{}", config.getBucket());
    }

    public void checkHealth() {
        s3.headBucket(HeadBucketRequest.builder().bucket(config.getBucket()).build());
    }

    public String getBucket() {
        return config.getBucket();
    }

    @Override
    public void put(String relativePath, Path source) throws IOException {
        String key = objectKey(relativePath);
        long size = Files.size(source);
        long threshold = Math.max(MIN_MULTIPART_PART_BYTES, config.getMultipartThresholdBytes());
        try {
            if (size == 0) {
                s3.putObject(PutObjectRequest.builder()
                        .bucket(config.getBucket())
                        .key(key)
                        .contentLength(0L)
                        .build(), RequestBody.empty());
            } else if (size <= threshold) {
                s3.putObject(PutObjectRequest.builder()
                        .bucket(config.getBucket())
                        .key(key)
                        .contentLength(size)
                        .build(), RequestBody.fromFile(source));
            } else {
                uploadMultipart(key, source, size);
            }
            Files.deleteIfExists(source);
        } catch (S3Exception e) {
            throw new IOException("写入 S3 对象失败 [" + key + "]: " + e.getMessage(), e);
        }
    }

    private void uploadMultipart(String key, Path source, long totalSize) throws IOException {
        int partSize = (int) Math.min(MAX_PART_BUFFER_BYTES,
                Math.max(MIN_MULTIPART_PART_BYTES, config.getMultipartPartBytes()));
        CreateMultipartUploadResponse createResp = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(config.getBucket())
                        .key(key)
                        .build());
        String uploadId = createResp.uploadId();
        List<CompletedPart> completedParts = new ArrayList<>();
        byte[] buffer = new byte[partSize];

        try (InputStream in = Files.newInputStream(source)) {
            int partNumber = 1;
            while (true) {
                int read = readFully(in, buffer);
                if (read == 0) {
                    break;
                }
                UploadPartResponse partResp = s3.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(config.getBucket())
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength((long) read)
                                .build(),
                        RequestBody.fromByteBuffer(ByteBuffer.wrap(buffer, 0, read)));
                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(partResp.eTag())
                        .build());
                partNumber++;
            }
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(config.getBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
            log.debug("S3 分片上传完成：key={} size={} parts={}", key, totalSize, completedParts.size());
        } catch (Exception e) {
            try {
                s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(config.getBucket())
                        .key(key)
                        .uploadId(uploadId)
                        .build());
            } catch (Exception abortEx) {
                log.warn("终止 S3 分片上传失败：key={} uploadId={}", key, uploadId, abortEx);
            }
            if (e instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("S3 分片上传失败 [" + key + "]: " + e.getMessage(), e);
        }
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n < 0) {
                break;
            }
            offset += n;
        }
        return offset;
    }

    @Override
    public InputStream open(String relativePath) throws IOException {
        String key = objectKey(relativePath);
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(config.getBucket())
                    .key(key)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new IOException("S3 对象不存在：" + key, e);
        } catch (S3Exception e) {
            throw new IOException("读取 S3 对象失败 [" + key + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream openRange(String relativePath, long offset, long length) throws IOException {
        if (length <= 0) {
            return InputStream.nullInputStream();
        }
        String key = objectKey(relativePath);
        long end = offset + length - 1;
        String range = "bytes=" + offset + "-" + end;
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(config.getBucket())
                    .key(key)
                    .range(range)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new IOException("S3 对象不存在：" + key, e);
        } catch (S3Exception e) {
            throw new IOException("定位读取 S3 对象失败 [" + key + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return head(relativePath) != null;
    }

    @Override
    public long size(String relativePath) {
        HeadObjectResponse head = head(relativePath);
        return head == null || head.contentLength() == null ? -1L : head.contentLength();
    }

    @Override
    public boolean delete(String relativePath) {
        if (!exists(relativePath)) {
            return false;
        }
        String key = objectKey(relativePath);
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(config.getBucket())
                    .key(key)
                    .build());
            return true;
        } catch (S3Exception e) {
            log.warn("删除 S3 对象失败：{}", key, e);
            return false;
        }
    }

    @Override
    public List<String> listFilePaths() {
        List<String> paths = new ArrayList<>();
        try {
            var pages = s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                    .bucket(config.getBucket())
                    .prefix(keyPrefix)
                    .build());
            for (var page : pages) {
                for (S3Object obj : page.contents()) {
                    String k = obj.key();
                    if (!k.startsWith(keyPrefix)) {
                        continue;
                    }
                    String rel = k.substring(keyPrefix.length());
                    int slash = rel.lastIndexOf('/');
                    String name = slash >= 0 ? rel.substring(slash + 1) : rel;
                    if (SAFE_UUID_NAME.matcher(name).matches()) {
                        paths.add(rel);
                    }
                }
            }
        } catch (S3Exception e) {
            log.warn("列举 S3 对象失败：prefix={}", keyPrefix, e);
        }
        return paths;
    }

    @Override
    public boolean fileOlderThan(String relativePath, Duration age) {
        HeadObjectResponse head = head(relativePath);
        if (head == null || head.lastModified() == null) {
            return false;
        }
        return head.lastModified().isBefore(Instant.now().minus(age));
    }

    @Override
    public String backendName() {
        return "s3";
    }

    private HeadObjectResponse head(String relativePath) {
        String key = objectKey(relativePath);
        try {
            return s3.headObject(HeadObjectRequest.builder()
                    .bucket(config.getBucket())
                    .key(key)
                    .build());
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    String objectKey(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BizException(10003, "非法的存储路径");
        }
        String normalized = relativePath.replace('\\', '/').replaceAll("^/+", "");
        String[] parts = normalized.split("/", -1);
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p) || "..".equals(p)) {
                throw new BizException(10003, "非法的存储路径");
            }
        }
        return keyPrefix + normalized;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String p = prefix.replace('\\', '/').replaceAll("^/+", "");
        if (!p.isEmpty() && !p.endsWith("/")) {
            p += "/";
        }
        return p;
    }
}
