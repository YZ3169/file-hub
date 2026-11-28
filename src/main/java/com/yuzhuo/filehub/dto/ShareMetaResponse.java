package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;

/**
 * 匿名查链接状态。刻意不返回文件名/大小/所有者，防止只拿到链接就能探测内容。
 */
public record ShareMetaResponse(String state,
                                LocalDateTime expiresAt,
                                boolean locked,
                                LocalDateTime lockedUntil,
                                boolean requireCode,
                                Long maxDownloads,
                                long downloadCount) {
    public ShareMetaResponse(String state, LocalDateTime expiresAt,
                             boolean locked, LocalDateTime lockedUntil) {
        this(state, expiresAt, locked, lockedUntil, true, null, 0L);
    }
}
