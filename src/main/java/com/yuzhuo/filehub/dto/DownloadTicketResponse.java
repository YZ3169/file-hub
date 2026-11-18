package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;

public record DownloadTicketResponse(String ticket, LocalDateTime expiresAt, String url) {
}
