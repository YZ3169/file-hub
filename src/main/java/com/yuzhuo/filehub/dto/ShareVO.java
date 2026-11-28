package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;

/**
 * 「我的分享」列表项。不包含提取码（库里只有 BCrypt 摘要）。
 */
public record ShareVO(String token,
                      String fileId,
                      String fileName,
                      Long fileSize,
                      LocalDateTime expiresAt,
                      String state,
                      long downloadCount,
                      boolean locked,
                      LocalDateTime lockedUntil,
                      LocalDateTime createTime,
                      boolean requireCode,
                      Long maxDownloads) {
    public ShareVO(String token, String fileId, String fileName, Long fileSize,
                   LocalDateTime expiresAt, String state, long downloadCount,
                   boolean locked, LocalDateTime lockedUntil, LocalDateTime createTime) {
        this(token, fileId, fileName, fileSize, expiresAt, state, downloadCount,
                locked, lockedUntil, createTime, true, null);
    }
}
