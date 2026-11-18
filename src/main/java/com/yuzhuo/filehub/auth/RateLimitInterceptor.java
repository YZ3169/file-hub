package com.yuzhuo.filehub.auth;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在 Spring Security 鉴权之后、Controller 之前限流，可以拿到已认证用户。
 *
 * <p>multipart 请求体在进入 Interceptor 前已经被容器解析，所以这里限制的是请求次数，
 * 不是字节速率；分片本身仍有大小上限。</p>
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    private final RateLimitProperties properties;

    private final SecurityJsonWriter jsonWriter;

    private final MeterRegistry meters;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/")) {
            return true;
        }

        int limit;
        long windowSeconds;
        String scope;
        boolean byIp = false;

        if ("/api/auth/login".equals(path)) {
            limit = properties.getLoginPerMinute();
            windowSeconds = 60;
            scope = "login";
            byIp = true;
        } else if ("/api/auth/register".equals(path)) {
            limit = properties.getRegisterPerHour();
            windowSeconds = 3600;
            scope = "register";
            byIp = true;
        } else if ("/api/file/chunk".equals(path)) {
            limit = properties.getChunkPerMinute();
            windowSeconds = 60;
            scope = "chunk";
        } else if ("/api/file/init".equals(path)) {
            limit = properties.getInitPerMinute();
            windowSeconds = 60;
            scope = "init";
        } else {
            limit = properties.getApiPerMinute();
            windowSeconds = 60;
            scope = "api";
        }

        if (limit <= 0) {
            return true;
        }

        String subject = byIp ? "ip:" + clientIp(request) : subject(request);
        RateLimiter.Decision decision =
                rateLimiter.tryAcquire(scope + ":" + subject, limit, windowSeconds);
        if (decision.allowed()) {
            return true;
        }
        meters.counter("filehub.rate_limit.rejected", "scope", scope).increment();
        jsonWriter.rateLimited(response, decision.retryAfterSeconds());
        return false;
    }

    /** 已登录优先按用户计数，避免同一出口 IP 下的用户互相影响 */
    private String subject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal user) {
            return "u:" + user.id();
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        if (properties.isTrustForwardedHeaders()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = comma >= 0 ? forwarded.substring(0, comma) : forwarded;
                if (!first.isBlank()) {
                    return first.trim();
                }
            }
        }
        return request.getRemoteAddr();
    }
}
