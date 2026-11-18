package com.yuzhuo.filehub.common;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 按 key 分段的读写锁。
 *
 * <p>单实例部署下，用它串行化同一个文件的落盘、合并、删除和清理：
 * 分片上传之间共享读锁（4 个并发分片互不阻塞），合并 / 删除 / 清理拿写锁。
 * 于是「合并时还有分片落盘」「清理刚好把正在提交分片的任务删掉」这类竞态，
 * 在进入磁盘操作之前就被挡住。</p>
 *
 * <p>为什么不直接用数据库行锁：合并是秒级的长操作，不能占着数据库连接和行锁；
 * 而且分片之间必须允许并发，行锁会把它们串成一条线。代价是它只在<b>单 JVM</b>内有效，
 * 多实例部署要换成分布式锁或对象存储的条件写。</p>
 */
@Component
public class FileLockRegistry {

    // 不为每个 fileId 建锁对象，避免任务多了以后锁无限增长；不同 key 撞到同一段
    // 只会多等一会儿，不影响正确性。
    private static final int STRIPES = 256;

    private final ReentrantReadWriteLock[] stripes = new ReentrantReadWriteLock[STRIPES];

    public FileLockRegistry() {
        for (int i = 0; i < STRIPES; i++) {
            // 公平锁：写请求排队后，新来的读请求不会再插队，避免清理/合并被分片洪峰饿死
            stripes[i] = new ReentrantReadWriteLock(true);
        }
    }

    public <T> T read(String key, Supplier<T> action) {
        Lock lock = stripe(key).readLock();
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void readVoid(String key, Runnable action) {
        read(key, () -> {
            action.run();
            return null;
        });
    }

    public <T> T write(String key, Supplier<T> action) {
        Lock lock = stripe(key).writeLock();
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void writeVoid(String key, Runnable action) {
        write(key, () -> {
            action.run();
            return null;
        });
    }

    private ReentrantReadWriteLock stripe(String key) {
        int h = Objects.hashCode(key);
        h ^= (h >>> 16);
        return stripes[Math.floorMod(h, STRIPES)];
    }
}
