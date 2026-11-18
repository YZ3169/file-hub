package com.yuzhuo.filehub.auth;

/**
 * 固定窗口限流抽象。Phase 2 的多实例模式会换成 Redis 实现，接口保持不变。
 */
public interface RateLimiter {

    Decision tryAcquire(String key, int limit, long windowSeconds);

    record Decision(boolean allowed, long retryAfterSeconds) {
    }
}
