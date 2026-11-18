package com.yuzhuo.filehub.common;

import java.util.function.Supplier;

/**
 * 按 key 的读写锁抽象。
 *
 * <p>单机模式用 JVM 内的 {@link FileLockRegistry}；多实例模式用 Redis 的
 * {@code RedisLockRegistry}。业务代码只依赖这个接口，换实现不改调用点。</p>
 *
 * <p>{@link #tryWrite} 用于定时清理抢主：抢不到锁直接返回 false，不阻塞等待。</p>
 */
public interface LockRegistry {

    <T> T read(String key, Supplier<T> action);

    void readVoid(String key, Runnable action);

    <T> T write(String key, Supplier<T> action);

    void writeVoid(String key, Runnable action);

    /**
     * 尝试获取写锁并执行；锁被别的线程/实例持有则立即返回 false，不执行 action。
     */
    boolean tryWrite(String key, Runnable action);
}
