package com.yuzhuo.filehub.webdav;

import com.yuzhuo.filehub.auth.AuthException;
import com.yuzhuo.filehub.auth.AuthService;
import com.yuzhuo.filehub.auth.LocalRateLimiter;
import com.yuzhuo.filehub.auth.RateLimitProperties;
import com.yuzhuo.filehub.auth.TokenService;
import com.yuzhuo.filehub.dto.FolderTreeResponse;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.FileFolderMapper;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.service.AuditService;
import com.yuzhuo.filehub.service.FileStorageService;
import com.yuzhuo.filehub.service.FolderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebDavFilterTest {

    @Mock
    AuthService authService;
    @Mock
    TokenService tokenService;
    @Mock
    FileStorageService fileStorageService;
    @Mock
    FolderService folderService;
    @Mock
    FileInfoMapper fileInfoMapper;
    @Mock
    FileFolderMapper fileFolderMapper;
    @Mock
    AuditService auditService;

    private RateLimitProperties props;
    private SimpleMeterRegistry meters;
    private WebDavFilter filter;

    @BeforeEach
    void setUp() {
        props = new RateLimitProperties();
        props.setEnabled(true);
        props.setWebdavAuthFailPerMinute(2);
        props.setWebdavPerMinute(3);
        meters = new SimpleMeterRegistry();
        filter = new WebDavFilter(
                authService, tokenService, fileStorageService, folderService,
                fileInfoMapper, fileFolderMapper, auditService,
                new LocalRateLimiter(), props, meters);
    }

    @Test
    @DisplayName("WebDAV 连续错误密码触发 429 锁定与 Retry-After")
    void bruteForceAuthGetsRateLimited() throws Exception {
        when(authService.verifyCredentials("alice", "wrong"))
                .thenThrow(new AuthException(40004, "用户名或密码错误", HttpStatus.UNAUTHORIZED));
        String badBasic = "Basic " + Base64.getEncoder()
                .encodeToString("alice:wrong".getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("PROPFIND", "/dav/");
            req.addHeader(HttpHeaders.AUTHORIZATION, badBasic);
            req.setRemoteAddr("10.0.0.8");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, mock(FilterChain.class));
            assertEquals(401, resp.getStatus());
        }

        // 第 3 次失败触发 429，且后续同 IP 请求在锁定期内直接 429
        MockHttpServletRequest blocked = new MockHttpServletRequest("PROPFIND", "/dav/");
        blocked.addHeader(HttpHeaders.AUTHORIZATION, badBasic);
        blocked.setRemoteAddr("10.0.0.8");
        MockHttpServletResponse blockedResp = new MockHttpServletResponse();
        filter.doFilter(blocked, blockedResp, mock(FilterChain.class));
        assertEquals(429, blockedResp.getStatus());
        assertNotNull(blockedResp.getHeader("Retry-After"));
        assertEquals(1.0, meters.counter("filehub.rate_limit.rejected", "scope", "webdav-auth").count());

        // 即使换一个正确密码在同一 IP 锁定期内也会被挡在前面（不消耗 BCrypt）
        MockHttpServletRequest lockedReq = new MockHttpServletRequest("PROPFIND", "/dav/");
        lockedReq.addHeader(HttpHeaders.AUTHORIZATION, badBasic);
        lockedReq.setRemoteAddr("10.0.0.8");
        MockHttpServletResponse lockedResp = new MockHttpServletResponse();
        filter.doFilter(lockedReq, lockedResp, mock(FilterChain.class));
        assertEquals(429, lockedResp.getStatus());
        verify(authService, org.mockito.Mockito.times(3)).verifyCredentials(any(), any());
    }

    @Test
    @DisplayName("WebDAV 已认证用户高频请求超过 webdavPerMinute 返回 429")
    void authenticatedRequestsRespectRateLimit() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(7L);
        user.setUsername("bob");
        user.setRole(UserAccount.ROLE_USER);
        user.setEnabled(1);
        when(authService.verifyCredentials("bob", "ok")).thenReturn(user);
        when(folderService.tree(any(), any(), any(), any(), any()))
                .thenReturn(new FolderTreeResponse("", List.of(), List.of(), List.of()));

        String goodBasic = "Basic " + Base64.getEncoder()
                .encodeToString("bob:ok".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("PROPFIND", "/dav/");
            req.addHeader(HttpHeaders.AUTHORIZATION, goodBasic);
            req.setRemoteAddr("10.0.0.9");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, mock(FilterChain.class));
            assertEquals(207, resp.getStatus());
        }

        MockHttpServletRequest fourth = new MockHttpServletRequest("PROPFIND", "/dav/");
        fourth.addHeader(HttpHeaders.AUTHORIZATION, goodBasic);
        fourth.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse fourthResp = new MockHttpServletResponse();
        filter.doFilter(fourth, fourthResp, mock(FilterChain.class));
        assertEquals(429, fourthResp.getStatus());
        assertNotNull(fourthResp.getHeader("Retry-After"));
    }
}
