package com.yuzhuo.filehub.dto;

import com.yuzhuo.filehub.entity.FileInfo;

import java.time.LocalDateTime;

/**
 * 对外的文件视图。注意**不返回 storage_path**——那是服务端内部的存储布局，
 * 暴露出去等于把目录结构送给调用方。要下载就走 /api/file/{fileId}/download。
 */
public record FileVO(
        String fileId,
        String fileName,
        long fileSize,
        String fileHash,
        int status,
        LocalDateTime createTime
) {
    public static FileVO from(FileInfo f) {
        return new FileVO(f.getFileId(), f.getFileName(), f.getFileSize(),
                f.getFileHash(), f.getStatus(), f.getCreateTime());
    }
}
