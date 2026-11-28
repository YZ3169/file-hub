package com.yuzhuo.filehub.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 成品文件的存储抽象——只描述「对象存储」语义（按 key 存取一段不透明字节），
 * 刻意不暴露任何 File / Path 概念。
 *
 * <p>当前只有 {@link LocalDiskStorage} 一个实现（落在本地磁盘）。
 * 想换成阿里云 OSS / MinIO 时，只需要再写一个实现，业务层一行不用改——
 * 这也是这个项目相对于「直接调 OSS SDK」的意义所在。</p>
 */
public interface StorageBackend {

    /** 把本地一个已经拼好的临时文件存成 relativePath。实现应当是"移动"而不是"拷贝" */
    void put(String relativePath, java.nio.file.Path source) throws IOException;

    /** 打开读取流，调用方负责关闭 */
    InputStream open(String relativePath) throws IOException;

    /**
     * 从 offset 开始打开读取流。默认实现用 skip 跳过前缀，本地磁盘会覆盖成真正的定位读取；
     * 调用方仍应按需要限制读取长度并负责关闭。
     */
    default InputStream openRange(String relativePath, long offset, long length) throws IOException {
        InputStream in = open(relativePath);
        try {
            skipFully(in, offset);
            return in;
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new IOException("文件实际长度小于索引记录");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    boolean exists(String relativePath);

    long size(String relativePath);

    /** 删除。文件本来就不存在时返回 false，不抛异常（幂等） */
    boolean delete(String relativePath);

    /** 列出存储中所有成品相对路径（供孤儿成品回收扫描） */
    default List<String> listFilePaths() {
        return List.of();
    }

    /** 成品是否已超过 age 没有变化（用于孤儿文件回收宽限期） */
    default boolean fileOlderThan(String relativePath, Duration age) {
        return false;
    }

    /** 存储后端自己的标识，写进日志和接口响应里方便排查 */
    String backendName();
}
