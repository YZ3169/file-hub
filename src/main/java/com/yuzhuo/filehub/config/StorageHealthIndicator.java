package com.yuzhuo.filehub.config;

import com.yuzhuo.filehub.storage.S3StorageBackend;
import com.yuzhuo.filehub.storage.StorageBackend;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 存储与分布式锁的健康检查（{@code /actuator/health} 的 {@code storage} 项）。
 *
 * <p>检查本地暂存根目录可写（所有模式都需要接收分片和合并）、成品存储后端（本地或 S3 Bucket 可达），
 * 以及集群模式下 Redis 能连。本地模式不创建 RedissonClient，redis 显示 disabled。</p>
 */
@Component
public class StorageHealthIndicator implements HealthIndicator {

    private final StorageProperties storageProperties;

    private final ClusterProperties clusterProperties;

    private final ObjectProvider<RedissonClient> redissonClient;

    private final ObjectProvider<StorageBackend> storageBackend;

    @org.springframework.beans.factory.annotation.Autowired
    public StorageHealthIndicator(StorageProperties storageProperties,
                                  ClusterProperties clusterProperties,
                                  ObjectProvider<RedissonClient> redissonClient,
                                  ObjectProvider<StorageBackend> storageBackend) {
        this.storageProperties = storageProperties;
        this.clusterProperties = clusterProperties;
        this.redissonClient = redissonClient;
        this.storageBackend = storageBackend;
    }

    public StorageHealthIndicator(StorageProperties storageProperties,
                                  ClusterProperties clusterProperties,
                                  ObjectProvider<RedissonClient> redissonClient) {
        this(storageProperties, clusterProperties, redissonClient, null);
    }

    @Override
    public Health health() {
        Path root = Paths.get(storageProperties.getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Path probe = Files.createTempFile(root, ".health-", ".tmp");
            Files.deleteIfExists(probe);
        } catch (IOException | SecurityException e) {
            return Health.down(e).withDetail("root", root.toString()).build();
        }

        StorageBackend backend = storageBackend == null ? null : storageBackend.getIfAvailable();
        Health.Builder builder = Health.up()
                .withDetail("root", root.toString())
                .withDetail("backend", backend == null ? "local-disk" : backend.backendName());

        if (backend instanceof S3StorageBackend s3Backend) {
            try {
                s3Backend.checkHealth();
                builder.withDetail("s3Bucket", s3Backend.getBucket());
            } catch (RuntimeException e) {
                return Health.down(e)
                        .withDetail("root", root.toString())
                        .withDetail("backend", "s3")
                        .withDetail("s3Bucket", s3Backend.getBucket())
                        .build();
            }
        }

        RedissonClient client = redissonClient.getIfAvailable();
        if (client == null) {
            return builder.withDetail("redis", "disabled").build();
        }
        try {
            client.getKeys().countExists(clusterProperties.getKeyPrefix() + "health:probe");
            return builder.withDetail("redis", "up").build();
        } catch (RuntimeException e) {
            return Health.down(e).withDetail("redis", "down").build();
        }
    }
}
