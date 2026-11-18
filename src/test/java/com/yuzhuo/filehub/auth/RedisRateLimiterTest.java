package com.yuzhuo.filehub.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

    @Mock
    RedissonClient client;

    @Mock
    RScript script;

    RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new RedisRateLimiter(client, "test:");
        // 非正数限额不会访问 Redis，这里用 lenient 允许多数用例共享该 stub
        org.mockito.Mockito.lenient()
                .when(client.getScript(org.mockito.ArgumentMatchers.any(
                        org.redisson.client.codec.Codec.class)))
                .thenReturn(script);
    }

    @Test
    @DisplayName("脚本计数未超限时放行")
    void allowsUnderLimit() {
        when(script.eval(any(), anyString(), any(), anyList(), any()))
                .thenReturn(List.of(3L, 59_000L));

        RateLimiter.Decision decision = limiter.tryAcquire("login:ip:1", 10, 60);

        assertTrue(decision.allowed());
        assertEquals(0, decision.retryAfterSeconds());
    }

    @Test
    @DisplayName("超过上限时拒绝，并按 TTL 向上取整给出重试秒数")
    void rejectsOverLimitWithRetryAfter() {
        when(script.eval(any(), anyString(), any(), anyList(), any()))
                .thenReturn(List.of(11L, 42_100L));

        RateLimiter.Decision decision = limiter.tryAcquire("login:ip:1", 10, 60);

        assertFalse(decision.allowed());
        assertEquals(43, decision.retryAfterSeconds());
    }

    @Test
    @DisplayName("Redis 不可用时返回 503，而不是静默放行")
    void failsClosedWhenRedisDown() {
        when(script.eval(any(), anyString(), any(), anyList(), any()))
                .thenThrow(new RedisException("down"));

        AuthException e = assertThrows(AuthException.class,
                () -> limiter.tryAcquire("login:ip:1", 10, 60));

        assertEquals(50003, e.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
    }

    @Test
    @DisplayName("非正数限额不访问 Redis")
    void nonPositiveLimitAllows() {
        assertTrue(limiter.tryAcquire("any", 0, 60).allowed());
        assertTrue(limiter.tryAcquire("any", 1, 0).allowed());
    }

    @Test
    @DisplayName("脚本返回空结果按故障处理")
    void emptyResultFailsClosed() {
        when(script.eval(any(), anyString(), eq(RScript.ReturnType.MULTI), anyList(), any()))
                .thenReturn(null);

        assertThrows(AuthException.class, () -> limiter.tryAcquire("api:u:1", 10, 60));
    }
}
