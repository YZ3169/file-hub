package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.entity.UserSession;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import com.yuzhuo.filehub.mapper.UserSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    UserSessionMapper sessionMapper;

    @Mock
    UserAccountMapper userMapper;

    TokenService service;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenTtlHours(3);
        service = new TokenService(sessionMapper, userMapper, properties);
    }

    @Test
    @DisplayName("签发 Token：数据库只存 SHA-256，不存原始 Token")
    void issueStoresOnlyHash() {
        UserAccount user = new UserAccount();
        user.setId(9L);

        TokenService.IssuedToken issued = service.issue(user);

        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(sessionMapper).insert(captor.capture());
        UserSession session = captor.getValue();
        assertEquals(43, issued.token().length());
        assertEquals(64, session.getTokenHash().length());
        assertEquals(OpaqueToken.hash(issued.token()), session.getTokenHash());
        assertNotEquals(issued.token(), session.getTokenHash());
        assertEquals(9L, session.getUserId());
        assertTrue(session.getExpiresAt().isAfter(LocalDateTime.now().plusHours(2)));
        assertTrue(session.getExpiresAt().isBefore(LocalDateTime.now().plusHours(3).plusMinutes(1)));
    }

    @Test
    @DisplayName("校验 Token：有效会话映射为 principal")
    void authenticateActiveSession() {
        String raw = "a".repeat(43);
        UserSession session = new UserSession();
        session.setUserId(9L);
        when(sessionMapper.findActive(eq(OpaqueToken.hash(raw)), any(LocalDateTime.class)))
                .thenReturn(session);
        when(userMapper.selectById(9L)).thenReturn(user(9L, "ops", UserAccount.ROLE_ADMIN, 1));

        UserPrincipal principal = service.authenticate(raw);

        assertEquals(9L, principal.id());
        assertEquals("ops", principal.username());
        assertEquals("ADMIN", principal.role());
        assertTrue(principal.isAdmin());
    }

    @Test
    @DisplayName("校验 Token：账号禁用或会话不存在都返回 null")
    void authenticateRejectsDisabledOrMissing() {
        String raw = "b".repeat(43);
        UserSession session = new UserSession();
        session.setUserId(9L);
        when(sessionMapper.findActive(eq(OpaqueToken.hash(raw)), any(LocalDateTime.class)))
                .thenReturn(session);
        when(userMapper.selectById(9L)).thenReturn(user(9L, "ops", UserAccount.ROLE_USER, 0));

        assertNull(service.authenticate(raw));

        when(sessionMapper.findActive(eq(OpaqueToken.hash("c".repeat(43))), any(LocalDateTime.class)))
                .thenReturn(null);
        assertNull(service.authenticate("c".repeat(43)));
    }

    @Test
    @DisplayName("畸形 Token 直接拒绝，不查数据库")
    void authenticateRejectsMalformedToken() {
        assertNull(service.authenticate(null));
        assertNull(service.authenticate("short"));
        assertNull(service.authenticate("x".repeat(300)));
        verifyNoInteractions(sessionMapper, userMapper);
    }

    @Test
    @DisplayName("撤销 Token 删除对应会话；空 Token 不做任何事")
    void revokeDeletesHashedSession() {
        service.revoke("raw-token-value");
        verify(sessionMapper).deleteByTokenHash(OpaqueToken.hash("raw-token-value"));

        service.revoke(null);
        service.revoke("  ");
        verify(sessionMapper, never()).deleteByTokenHash(OpaqueToken.hash("  "));
    }

    @Test
    @DisplayName("Authorization 头只接受 Bearer 方案")
    void extractBearer() {
        assertEquals("abc", TokenService.extractBearer("Bearer abc"));
        assertEquals("abc", TokenService.extractBearer("bearer abc"));
        assertEquals("", TokenService.extractBearer("Bearer "));
        assertNull(TokenService.extractBearer("Basic abc"));
        assertNull(TokenService.extractBearer("abc"));
        assertNull(TokenService.extractBearer(null));
    }

    private UserAccount user(long id, String username, String role, int enabled) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setEnabled(enabled);
        return user;
    }
}
