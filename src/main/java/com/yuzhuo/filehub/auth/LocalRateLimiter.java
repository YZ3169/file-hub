package com.yuzhuo.filehub.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单机固定窗口计数。key 里带窗口编号，窗口自然过期，不依赖定时清理。
 * 由 {@code ClusterConfig} 按 {@code file-hub.cluster.enabled} 条件装配。
 */
public class LocalRateLimiter implements RateLimiter {

    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(Duration.ofHours(2))
            .build();

    @Override
    public Decision tryAcquire(String key, int limit, long windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) {
            return new Decision(true, 0);
        }
        long now = System.currentTimeMillis() / 1000;
        long windowId = now / windowSeconds;
        // 每个窗口一个独立计数器；旧窗口由 Caffeine 到期回收
        String windowKey = key + ":" + windowId;
        AtomicInteger counter = counters.get(windowKey, k -> new AtomicInteger());
        int used = counter == null ? 0 : counter.incrementAndGet();
        if (used <= limit) {
            return new Decision(true, 0);
        }
        long retryAfter = windowSeconds - (now % windowSeconds) + 1;
        return new Decision(false, retryAfter);
    }
}
