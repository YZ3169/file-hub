package com.yuzhuo.filehub.auth;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

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
        String shareSubject = null;

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
        } else if (path.startsWith("/api/public/share/")) {
            String ip = clientIp(request);

            // 第一道：按 IP 的匿名提取总闸。token 完全由客户端控制（还能用百分号编码
            // 造出无数种写法），只按 token 分桶等于把限流器交给攻击者，必须另有总上限。
            int globalLimit = properties.getShareGlobalPerMinute();
            if (globalLimit > 0) {
                RateLimiter.Decision global =
                        rateLimiter.tryAcquire("share-ip:ip:" + ip, globalLimit, 60);
                if (!global.allowed()) {
                    meters.counter("filehub.rate_limit.rejected", "scope", "share-ip").increment();
                    jsonWriter.rateLimited(response, global.retryAfterSeconds());
                    return false;
                }
            }

            // 第二道：按 IP + 分享 token 计数，NAT 后多人提取不同分享不互相拖累。
            // 真正的防爆破是 ShareService 里每分享的失败锁定。
            limit = properties.getSharePerMinute();
            windowSeconds = 60;
            scope = "share";
            shareSubject = "ip:" + ip + ":t:" + shareToken(path);
        } else {
            limit = properties.getApiPerMinute();
            windowSeconds = 60;
            scope = "api";
        }

        if (limit <= 0) {
            return true;
        }

        String subject = shareSubject != null
                ? shareSubject
                : (byIp ? "ip:" + clientIp(request) : subject(request));
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

    /**
     * 取 /api/public/share/{token}[/verify] 里的 token 并规范化。
     *
     * <p>{@code getRequestURI()} 是未解码的原始 URI，而 Controller 拿到的是解码后的值。
     * 不先解码的话，同一个 token 的 {@code %41BCD} / {@code ABCD} 等写法会落入不同的
     * 限流桶，等于白送攻击者无限额度。再统一转小写：MySQL 里 token 列是大小写敏感的
     * （ascii_bin），但升级前的旧库可能还不是，小写 key 至少能把两种口径归一。</p>
     */
    private String shareToken(String path) {
        String rest = path.substring("/api/public/share/".length());
        int slash = rest.indexOf('/');
        String raw = slash >= 0 ? rest.substring(0, slash) : rest;
        String token;
        try {
            token = UriUtils.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            token = raw;
        }
        token = token.toLowerCase(Locale.ROOT);
        return token.length() > 64 ? token.substring(0, 64) : token;
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
