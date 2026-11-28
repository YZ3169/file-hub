package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;

public record ZipTicketResponse(
        String ticket,
        LocalDateTime expiresAt,
        String downloadUrl,
        String zipName,
        int fileCount,
        long totalBytes
) {
}
