package com.yuzhuo.filehub.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析 Authorization: Bearer <opaque-token>。
 *
 * <p>注意：Filter Bean 会被 Spring Boot 自动注册到 Servlet 容器一次，SecurityConfig 里
 * 另外用一个 disabled 的 FilterRegistrationBean 阻止它绕过安全链重复执行。</p>
 */
@Component
@RequiredArgsConstructor
public class BearerTokenFilter extends OncePerRequestFilter {

    private final TokenService tokenService;

    private final SecurityJsonWriter writer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 不是 Bearer 方案就交给后续安全链决定（公开接口允许匿名，受保护接口由 EntryPoint 返回 401）
        if (authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = TokenService.extractBearer(authorization);
        if (token == null || token.isBlank()) {
            writer.unauthorized(response, "登录凭证格式错误");
            return;
        }

        UserPrincipal principal = tokenService.authenticate(token);
        if (principal == null) {
            writer.unauthorized(response, "登录已过期，请重新登录");
            return;
        }

        String role = principal.role() == null ? "USER" : principal.role();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }
}
