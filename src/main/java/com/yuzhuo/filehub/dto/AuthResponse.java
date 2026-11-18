package com.yuzhuo.filehub.dto;

import java.time.LocalDateTime;

public record AuthResponse(String token, LocalDateTime expiresAt, UserVO user) {
}
