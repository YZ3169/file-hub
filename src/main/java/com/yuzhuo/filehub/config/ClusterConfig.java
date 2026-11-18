package com.yuzhuo.filehub.config;

import com.yuzhuo.filehub.auth.LocalRateLimiter;
import com.yuzhuo.filehub.auth.RateLimiter;
import com.yuzhuo.filehub.auth.RedisRateLimiter;
import com.yuzhuo.filehub.common.FileLockRegistry;
import com.yuzhuo.filehub.common.LockRegistry;
import com.yuzhuo.filehub.storage.RedisLockRegistry;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 单机 / 多实例两套实现的装配开关。
 *
 * <p>只用一个开关 {@code file-hub.cluster.enabled} 决定锁和限流的实现：
 * 打开后两者同时切到 Redis。不能只切一个——只切锁会让限流各算各的，
 * 只切限流会让两个实例同时合并同一个文件。</p>
 */
@Configuration
public class ClusterConfig {

    @Bean
    @ConditionalOnProperty(name = "file-hub.cluster.enabled", havingValue = "false",
            matchIfMissing = true)
    public LockRegistry fileLockRegistry() {
        return new FileLockRegistry();
    }

    @Bean
    @ConditionalOnProperty(name = "file-hub.cluster.enabled", havingValue = "false",
            matchIfMissing = true)
    public RateLimiter localRateLimiter() {
        return new LocalRateLimiter();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "file-hub.cluster.enabled", havingValue = "true")
    public RedissonClient redissonClient(ClusterProperties properties) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(properties.getRedisAddress())
                .setDatabase(properties.getRedisDatabase())
                // 快速失败：Redis 不可用时业务直接报错，不要卡住几十秒
                .setConnectTimeout(3000)
                .setTimeout(3000)
                .setRetryAttempts(2)
                .setConnectionMinimumIdleSize(4)
                .setConnectionPoolSize(16);
        return Redisson.create(config);
    }

    @Bean
    @ConditionalOnProperty(name = "file-hub.cluster.enabled", havingValue = "true")
    public LockRegistry redisLockRegistry(RedissonClient client, ClusterProperties properties) {
        return new RedisLockRegistry(client, properties.getKeyPrefix());
    }

    @Bean
    @ConditionalOnProperty(name = "file-hub.cluster.enabled", havingValue = "true")
    public RateLimiter redisRateLimiter(RedissonClient client, ClusterProperties properties) {
        return new RedisRateLimiter(client, properties.getKeyPrefix());
    }
}
