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

    // 匿名分享按「IP + token」计数，token 可由攻击者构造出大量不同 key；
    // 上限给宽一些，避免随机 token 把登录/上传等其它 scope 的计数器挤出缓存。
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .maximumSize(200_000)
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
