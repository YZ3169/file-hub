package com.yuzhuo.filehub.config;

import lombok.RequiredArgsConstructor;
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
 * <p>只检查「根目录能写」和「集群模式下 Redis 能连」——这两件事坏了，上传/合并一定失败，
 * 但进程本身还活着，所以不能只看端口通不通。本地模式不创建 RedissonClient，redis 显示 disabled。</p>
 */
@Component
@RequiredArgsConstructor
public class StorageHealthIndicator implements HealthIndicator {

    private final StorageProperties storageProperties;

    private final ClusterProperties clusterProperties;

    private final ObjectProvider<RedissonClient> redissonClient;

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

        Health.Builder builder = Health.up().withDetail("root", root.toString());
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
