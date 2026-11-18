package com.yuzhuo.filehub.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuo.filehub.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Spring Security 过滤器链内无法走 @RestControllerAdvice，这里直接写统一的 Result JSON。
 */
@Component
@RequiredArgsConstructor
public class SecurityJsonWriter {

    private final ObjectMapper objectMapper;

    public void unauthorized(HttpServletResponse response, String message) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, 40001, message);
    }

    public void forbidden(HttpServletResponse response, String message) throws IOException {
        write(response, HttpStatus.FORBIDDEN, 40003, message);
    }

    public void rateLimited(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfterSeconds)));
        write(response, HttpStatus.TOO_MANY_REQUESTS, 40002, "请求过于频繁，请稍后再试");
    }

    private void write(HttpServletResponse response, HttpStatus status, int code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.fail(code, message));
    }
}
