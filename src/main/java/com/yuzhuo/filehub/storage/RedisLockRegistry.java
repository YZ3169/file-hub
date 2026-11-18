package com.yuzhuo.filehub.storage;

import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.common.LockRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.util.function.Supplier;

/**
 * Redis 分布式读写锁（Redisson {@code RReadWriteLock}）。
 *
 * <p>锁的 key 统一加前缀（默认 {@code filehub:lock:}）。{@code lock()} 不指定 lease time，
 * 由 Redisson watchdog 自动续期——合并大文件可能远超任意固定租期，固定租期会让锁在
 * 操作中途失效。</p>
 *
 * <p>Redis 故障时抛 {@link BizException(20008)}，明确失败；<b>绝不</b>退回 JVM 本地锁，
 * 否则两个实例会同时写同一份物理文件。</p>
 */
@Slf4j
public class RedisLockRegistry implements LockRegistry {

    private final RedissonClient client;

    private final String keyPrefix;

    public RedisLockRegistry(RedissonClient client, String keyPrefix) {
        this.client = client;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public <T> T read(String key, Supplier<T> action) {
        return withLock(readLock(key), action);
    }

    @Override
    public void readVoid(String key, Runnable action) {
        read(key, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T write(String key, Supplier<T> action) {
        return withLock(writeLock(key), action);
    }

    @Override
    public void writeVoid(String key, Runnable action) {
        write(key, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public boolean tryWrite(String key, Runnable action) {
        RLock lock = writeLock(key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock();
            if (!acquired) {
                return false;
            }
            action.run();
            return true;
        } catch (RedisException e) {
            throw unavailable(e);
        } finally {
            if (acquired) {
                unlockQuietly(lock);
            }
        }
    }

    private <T> T withLock(RLock lock, Supplier<T> action) {
        boolean acquired = false;
        try {
            lock.lock();
            acquired = true;
            return action.get();
        } catch (RedisException e) {
            throw unavailable(e);
        } finally {
            if (acquired) {
                unlockQuietly(lock);
            }
        }
    }

    private RLock readLock(String key) {
        return client.getReadWriteLock(keyPrefix + "lock:" + key).readLock();
    }

    private RLock writeLock(String key) {
        return client.getReadWriteLock(keyPrefix + "lock:" + key).writeLock();
    }

    /**
     * 只解锁当前线程持有的锁：如果 Redis 连接断过、watchdog 丢了锁，
     * 直接 unlock 会抛 IllegalMonitorState，把它和真正的业务异常混在一起。
     */
    private void unlockQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RedisException e) {
            log.warn("释放分布式锁失败 key={}", lock.getName(), e);
        }
    }

    private BizException unavailable(Exception e) {
        log.error("分布式锁不可用，拒绝继续执行业务", e);
        return new BizException(20008, "分布式锁不可用，请稍后重试");
    }
}
