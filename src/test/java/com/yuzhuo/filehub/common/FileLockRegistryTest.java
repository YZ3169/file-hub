package com.yuzhuo.filehub.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileLockRegistryTest {

    private final FileLockRegistry locks = new FileLockRegistry();

    @Test
    @DisplayName("同一 key 的写锁互斥：第二个合并必须等第一个释放")
    void writeLocksAreExclusive() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        Thread first = new Thread(() -> locks.writeVoid("file-a", () -> {
            firstEntered.countDown();
            await(releaseFirst);
        }));
        first.start();
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

        Thread second = new Thread(() -> locks.writeVoid("file-a", secondEntered::countDown));
        second.start();
        assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS), "写锁没释放前第二个写操作不能进入");

        releaseFirst.countDown();
        first.join(2000);
        assertTrue(secondEntered.await(2, TimeUnit.SECONDS), "第一个释放后第二个写操作应立刻进入");
        second.join(2000);
    }

    @Test
    @DisplayName("分片读锁可并发，但合并写锁要等它们全部结束")
    void readersShareUntilWriter() throws Exception {
        CountDownLatch readerEntered = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        Thread reader = new Thread(() -> locks.readVoid("file-b", () -> {
            readerEntered.countDown();
            await(releaseReader);
        }));
        reader.start();
        assertTrue(readerEntered.await(2, TimeUnit.SECONDS));

        CountDownLatch secondReaderEntered = new CountDownLatch(1);
        Thread secondReader = new Thread(() -> locks.readVoid("file-b", secondReaderEntered::countDown));
        secondReader.start();
        assertTrue(secondReaderEntered.await(2, TimeUnit.SECONDS), "读锁之间不互斥");
        secondReader.join(2000);

        CountDownLatch writerEntered = new CountDownLatch(1);
        Thread writer = new Thread(() -> locks.writeVoid("file-b", writerEntered::countDown));
        writer.start();
        assertFalse(writerEntered.await(200, TimeUnit.MILLISECONDS), "写锁必须等正在落盘的分片结束");

        releaseReader.countDown();
        reader.join(2000);
        assertTrue(writerEntered.await(2, TimeUnit.SECONDS));
        writer.join(2000);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
