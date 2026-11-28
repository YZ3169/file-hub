package com.yuzhuo.filehub.webdav;

import com.yuzhuo.filehub.auth.AuthService;
import com.yuzhuo.filehub.auth.RateLimitProperties;
import com.yuzhuo.filehub.auth.RateLimiter;
import com.yuzhuo.filehub.auth.TokenService;
import com.yuzhuo.filehub.mapper.FileFolderMapper;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.service.AuditService;
import com.yuzhuo.filehub.service.FileStorageService;
import com.yuzhuo.filehub.service.FolderService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebDavConfig {

    @Bean
    public FilterRegistrationBean<WebDavFilter> webDavFilterRegistration(
            AuthService authService,
            TokenService tokenService,
            FileStorageService fileStorageService,
            FolderService folderService,
            FileInfoMapper fileInfoMapper,
            FileFolderMapper fileFolderMapper,
            AuditService auditService,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties,
            MeterRegistry meterRegistry) {
        WebDavFilter filter = new WebDavFilter(
                authService, tokenService, fileStorageService, folderService,
                fileInfoMapper, fileFolderMapper, auditService,
                rateLimiter, rateLimitProperties, meterRegistry);
        FilterRegistrationBean<WebDavFilter> bean = new FilterRegistrationBean<>(filter);
        bean.addUrlPatterns("/dav", "/dav/*");
        bean.setName("webDavFilter");
        // 运行在 Spring Security FilterChain（-100）之后，保持单 Servlet 上下文不干扰 MockMvc。
        bean.setOrder(10);
        return bean;
    }
}
