package com.yuzhuo.filehub.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRateLimiterTest {

    private final LocalRateLimiter limiter = new LocalRateLimiter();

    @Test
    @DisplayName("固定窗口：超过上限后拒绝，并给出 1..window 秒的重试时间")
    void rejectsAfterLimit() {
        assertTrue(limiter.tryAcquire("login:ip:1", 2, 60).allowed());
        assertTrue(limiter.tryAcquire("login:ip:1", 2, 60).allowed());

        RateLimiter.Decision rejected = limiter.tryAcquire("login:ip:1", 2, 60);
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterSeconds() >= 1 && rejected.retryAfterSeconds() <= 61);
    }

    @Test
    @DisplayName("不同 key 的计数互不影响")
    void keysAreIndependent() {
        assertTrue(limiter.tryAcquire("user:7", 1, 60).allowed());
        assertFalse(limiter.tryAcquire("user:7", 1, 60).allowed());
        assertTrue(limiter.tryAcquire("user:8", 1, 60).allowed());
    }

    @Test
    @DisplayName("非正数限额视为不限流")
    void nonPositiveLimitAllows() {
        assertTrue(limiter.tryAcquire("any", 0, 60).allowed());
        assertTrue(limiter.tryAcquire("any", -1, 60).allowed());
        assertTrue(limiter.tryAcquire("any", 1, 0).allowed());
    }
}
