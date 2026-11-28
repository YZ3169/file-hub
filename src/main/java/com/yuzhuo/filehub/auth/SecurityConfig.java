package com.yuzhuo.filehub.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final BearerTokenFilter bearerTokenFilter;

    private final SecurityJsonWriter jsonWriter;

    private final AuthProperties authProperties;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /** 放行 WebDAV（RFC 4918）扩展 HTTP 方法，避免被 StrictHttpFirewall 直接拦截返回 400 */
    @Bean
    public HttpFirewall allowWebDavHttpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHttpMethods(List.of(
                "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS",
                "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK"));
        return firewall;
    }

    /**
     * Filter Bean 默认会被 Spring Boot 注册成普通 Servlet Filter；这里禁用自动注册，
     * 只让 SecurityFilterChain 中的那一份执行，避免同一请求鉴权两次。
     */
    @Bean
    public FilterRegistrationBean<BearerTokenFilter> bearerTokenFilterRegistration(
            BearerTokenFilter filter) {
        FilterRegistrationBean<BearerTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                jsonWriter.unauthorized(response, "请先登录"))
                        .accessDeniedHandler((request, response, ex) ->
                                jsonWriter.forbidden(response, "没有权限执行该操作")))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.sameOrigin())
                        .referrerPolicy(ref -> ref.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "script-src 'self' 'unsafe-inline'; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data: blob:; "
                                        + "media-src 'self' blob:; "
                                        + "worker-src 'self' blob:; "
                                        + "frame-src 'self'; "
                                        + "object-src 'none'; "
                                        + "base-uri 'self'; "
                                        + "form-action 'self'; "
                                        + "frame-ancestors 'self'")))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/", "/index.html", "/assets/**", "/favicon.svg",
                            "/classic.html", "/download.html", "/error").permitAll();
                    if (authProperties.isDocsPublic()) {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/**").permitAll();
                    } else {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/**").hasRole("ADMIN");
                    }
                    // health 给容器探针用，不要求 Token；metrics/prometheus 仍要 ADMIN
                    auth.requestMatchers("/actuator/health", "/actuator/health/**",
                            "/actuator/info").permitAll()
                            .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                            .requestMatchers("/api/file/config").permitAll()
                            // 桌面外壳用独立随机凭据 + 回环校验签发本地目录授权，不走用户 Bearer 登录
                            .requestMatchers(HttpMethod.POST, "/api/desktop/sync/shell-grant").permitAll()
                            // 匿名提取：只放行公共前缀；管理接口 /api/share/** 仍需登录
                            .requestMatchers(HttpMethod.GET, "/api/public/share/*").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/public/share/*/verify").permitAll()
                            // 下载与打包下载接口由 Controller 内部校验 Bearer 或短期票据
                            .requestMatchers(HttpMethod.GET, "/api/file/*/download").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/file/zip").permitAll()
                            // WebDAV 由独立 Filter 走 HTTP Basic / Bearer 鉴权与限流
                            .requestMatchers("/dav", "/dav/**").permitAll()
                            .requestMatchers("/api/admin/**").hasRole("ADMIN")
                            .requestMatchers("/actuator/**").hasRole("ADMIN")
                            .requestMatchers("/api/**").authenticated()
                            .anyRequest().permitAll();
                })
                .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
