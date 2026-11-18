package com.yuzhuo.filehub.storage;

import com.yuzhuo.filehub.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisLockRegistryTest {

    @Mock
    RedissonClient client;

    @Mock
    RReadWriteLock readWriteLock;

    @Mock
    RLock writeLock;

    RedisLockRegistry locks;

    @BeforeEach
    void setUp() {
        locks = new RedisLockRegistry(client, "test:");
        when(client.getReadWriteLock("test:lock:file-a")).thenReturn(readWriteLock);
        when(readWriteLock.writeLock()).thenReturn(writeLock);
    }

    @Test
    @DisplayName("写锁加锁/解锁，并把原始 key 放进统一前缀")
    void writeLocksAndUnlocks() {
        when(writeLock.isHeldByCurrentThread()).thenReturn(true);

        String result = locks.write("file-a", () -> "ok");

        assertEquals("ok", result);
        verify(writeLock).lock();
        verify(writeLock).unlock();
    }

    @Test
    @DisplayName("业务抛异常时仍然释放锁")
    void unlocksWhenActionThrows() {
        when(writeLock.isHeldByCurrentThread()).thenReturn(true);

        assertThrows(IllegalStateException.class, () ->
                locks.write("file-a", () -> {
                    throw new IllegalStateException("boom");
                }));

        verify(writeLock).unlock();
    }

    @Test
    @DisplayName("tryWrite 抢不到锁时返回 false 且不执行")
    void tryWriteSkipsWhenLocked() {
        when(writeLock.tryLock()).thenReturn(false);

        boolean ran = locks.tryWrite("file-a", () -> {
            throw new AssertionError("不应该执行");
        });

        assertFalse(ran);
        verify(writeLock, never()).unlock();
    }

    @Test
    @DisplayName("tryWrite 抢到锁时执行并释放")
    void tryWriteRunsWhenFree() {
        when(writeLock.tryLock()).thenReturn(true);
        when(writeLock.isHeldByCurrentThread()).thenReturn(true);
        StringBuilder log = new StringBuilder();

        assertTrue(locks.tryWrite("file-a", () -> log.append("ran")));

        assertEquals("ran", log.toString());
        verify(writeLock).unlock();
    }

    @Test
    @DisplayName("Redis 故障时抛 20008，绝不退回本地锁静默继续")
    void redisFailureIsExplicit() {
        org.mockito.Mockito.doThrow(new RedisException("redis down")).when(writeLock).lock();

        BizException e = assertThrows(BizException.class,
                () -> locks.write("file-a", () -> "never"));

        assertEquals(20008, e.getCode());
        assertTrue(e.getMessage().contains("分布式锁不可用"));
    }
}
