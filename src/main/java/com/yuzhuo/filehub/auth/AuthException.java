package com.yuzhuo.filehub.auth;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 认证/授权异常：需要真实的 HTTP 401/403，而不是现有的「HTTP 200 + 业务码」。
 */
@Getter
public class AuthException extends RuntimeException {

    private final int code;

    private final HttpStatus status;

    public AuthException(int code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static AuthException unauthorized(String message) {
        return new AuthException(40001, message, HttpStatus.UNAUTHORIZED);
    }

    public static AuthException forbidden(String message) {
        return new AuthException(40003, message, HttpStatus.FORBIDDEN);
    }
}
