package com.yuzhuo.filehub.storage;

import java.io.IOException;
import java.io.InputStream;

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

    boolean exists(String relativePath);

    long size(String relativePath);

    /** 删除。文件本来就不存在时返回 false，不抛异常（幂等） */
    boolean delete(String relativePath);

    /** 存储后端自己的标识，写进日志和接口响应里方便排查 */
    String backendName();
}
