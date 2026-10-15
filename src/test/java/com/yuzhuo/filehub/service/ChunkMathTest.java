package com.yuzhuo.filehub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分片总数由服务端推导。这几个边界是线上最容易出事的地方：
 * 空文件、正好整除、多出 1 字节——算错一个就是"合并出来的文件少一截"。
 */
class ChunkMathTest {

    private static final int CHUNK = 5 * 1024 * 1024;   // 5MB

    @Test
    @DisplayName("空文件：0 片")
    void emptyFile() {
        assertEquals(0, FileStorageService.calcChunkTotal(0, CHUNK));
    }

    @Test
    @DisplayName("不足一片：1 片")
    void smallerThanOneChunk() {
        assertEquals(1, FileStorageService.calcChunkTotal(1, CHUNK));
        assertEquals(1, FileStorageService.calcChunkTotal(CHUNK - 1, CHUNK));
    }

    @Test
    @DisplayName("正好整除：不能多算一片")
    void exactMultiple() {
        assertEquals(1, FileStorageService.calcChunkTotal(CHUNK, CHUNK));
        assertEquals(4, FileStorageService.calcChunkTotal(CHUNK * 4L, CHUNK));
    }

    @Test
    @DisplayName("整除多 1 字节：必须进位成多一片")
    void justOverMultiple() {
        assertEquals(2, FileStorageService.calcChunkTotal(CHUNK + 1L, CHUNK));
        assertEquals(5, FileStorageService.calcChunkTotal(CHUNK * 4L + 1, CHUNK));
    }

    @Test
    @DisplayName("大文件不溢出 int（30GB / 5MB = 6144 片）")
    void largeFile() {
        long size = 30L * 1024 * 1024 * 1024;
        assertEquals(6144, FileStorageService.calcChunkTotal(size, CHUNK));
    }
}
