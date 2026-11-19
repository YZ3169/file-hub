package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;

/** 重置后的新提取码，同样只返回一次。 */
public record ResetShareCodeResponse(String token, String code, LocalDateTime expiresAt) {
}
