package com.yuzhuo.filehub.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageHealthIndicatorTest {

    @TempDir
    Path tempDir;

    private StorageProperties props(Path root) {
        StorageProperties props = new StorageProperties();
        props.setRoot(root.toString());
        return props;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<RedissonClient> noRedis() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    @DisplayName("root 可写、未启用集群：UP，redis 显示 disabled")
    void upWithoutRedis() {
        StorageHealthIndicator indicator =
                new StorageHealthIndicator(props(tempDir), new ClusterProperties(), noRedis());

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("disabled", health.getDetails().get("redis"));
    }

    @Test
    @DisplayName("集群模式下 Redis 连不通：整体 DOWN，而不是只看端口")
    void downWhenRedisThrows() throws IOException {
        RedissonClient client = mock(RedissonClient.class);
        when(client.getKeys()).thenThrow(new IllegalStateException("connection refused"));
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);

        Health health = new StorageHealthIndicator(props(tempDir), new ClusterProperties(), provider)
                .health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("down", health.getDetails().get("redis"));
    }

    @Test
    @DisplayName("集群模式下 Redis 可连：UP")
    void upWithRedis() {
        RedissonClient client = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(client.getKeys()).thenReturn(keys);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);

        Health health = new StorageHealthIndicator(props(tempDir), new ClusterProperties(), provider)
                .health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("up", health.getDetails().get("redis"));
    }

    @Test
    @DisplayName("root 位置被一个普通文件占住：DOWN")
    void downWhenRootNotDirectory() throws IOException {
        Path file = Files.createFile(tempDir.resolve("not-a-dir"));

        Health health = new StorageHealthIndicator(props(file), new ClusterProperties(), noRedis())
                .health();

        assertEquals(Status.DOWN, health.getStatus());
    }
}
