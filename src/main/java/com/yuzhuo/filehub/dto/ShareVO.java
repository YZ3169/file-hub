package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;

/**
 * 「我的分享」列表项。不包含提取码（库里只有 BCrypt 摘要）。
 *
 * @param state      ACTIVE / EXPIRED / CANCELLED / FILE_MISSING
 * @param locked     是否处于输错锁定中
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
                      LocalDateTime createTime) {
}
