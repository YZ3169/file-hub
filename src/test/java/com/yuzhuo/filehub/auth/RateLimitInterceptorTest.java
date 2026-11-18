package com.yuzhuo.filehub.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    RateLimiter limiter;

    RateLimitProperties properties;

    SecurityJsonWriter writer;

    RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        writer = new SecurityJsonWriter(new ObjectMapper());
        interceptor = new RateLimitInterceptor(limiter, properties, writer,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("登录按 IP 计数；超限返回 429 + Retry-After + 40002")
    void loginIsLimitedByIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(limiter.tryAcquire("login:ip:203.0.113.9", 10, 60))
                .thenReturn(new RateLimiter.Decision(false, 17));

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        verify(limiter).tryAcquire("login:ip:203.0.113.9", 10, 60);
        assertTrue(response.getHeader("Retry-After").equals("17"));
        assertTrue(response.getContentAsString().contains("\"code\":40002"));
    }

    @Test
    @DisplayName("已登录用户的分片请求按 userId 计数，不按 IP")
    void chunkIsLimitedByUser() throws Exception {
        UserPrincipal user = new UserPrincipal(7L, "alice", "USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/file/chunk");
        request.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(limiter.tryAcquire("chunk:u:7", 2400, 60))
                .thenReturn(new RateLimiter.Decision(true, 0));

        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(limiter).tryAcquire("chunk:u:7", 2400, 60);
    }

    @Test
    @DisplayName("关闭限流或非 /api 路径时直接放行")
    void bypassesWhenDisabledOrNotApi() throws Exception {
        properties.setEnabled(false);
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/auth/login");
        assertTrue(interceptor.preHandle(login, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(limiter);

        properties.setEnabled(true);
        MockHttpServletRequest staticRequest = new MockHttpServletRequest("GET", "/index.html");
        assertTrue(interceptor.preHandle(staticRequest, new MockHttpServletResponse(), new Object()));
        verify(limiter, never()).tryAcquire(org.mockito.ArgumentMatchers.anyString(),
                anyInt(), anyLong());
    }

    @Test
    @DisplayName("把 limiter 的 key 和窗口参数原样传下去")
    void forwardsConfiguredLimit() throws Exception {
        properties.setInitPerMinute(3);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/file/init");
        when(limiter.tryAcquire("init:ip:127.0.0.1", 3, 60))
                .thenReturn(new RateLimiter.Decision(true, 0));

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verify(limiter).tryAcquire("init:ip:127.0.0.1", 3, 60);
    }
}
