package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;

public record AdminUserVO(
        Long id,
        String username,
        String role,
        boolean enabled,
        long storageQuotaBytes,
        long usedBytes,
        LocalDateTime createdAt
) {
}
