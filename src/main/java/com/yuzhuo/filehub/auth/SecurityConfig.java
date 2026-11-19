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

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final BearerTokenFilter bearerTokenFilter;

    private final SecurityJsonWriter jsonWriter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/assets/**", "/favicon.svg",
                                "/classic.html", "/error").permitAll()
                        // 开发/演示环境开放接口文档；生产建议只在内网或改成 ADMIN（见 README）
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // health 给容器探针用，不要求 Token；metrics/prometheus 仍要 ADMIN
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/api/file/config").permitAll()
                        // 匿名提取：只放行公共前缀；管理接口 /api/share/** 仍需登录
                        .requestMatchers(HttpMethod.GET, "/api/public/share/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/share/*/verify").permitAll()
                        // 下载接口由 Controller 内部校验 Bearer 或短期票据
                        .requestMatchers(HttpMethod.GET, "/api/file/*/download").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
