package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.dto.AuthResponse;
import com.yuzhuo.filehub.dto.LoginRequest;
import com.yuzhuo.filehub.dto.RegisterRequest;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserAccountMapper userMapper;

    @Mock
    TokenService tokenService;

    PasswordEncoder encoder;

    AuthProperties properties;

    AuthService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        properties = new AuthProperties();
        service = new AuthService(userMapper, tokenService, encoder, properties);
        service.initDummyHash();
    }

    @Test
    @DisplayName("登录：用户不存在时统一返回 40004，不暴露用户名是否存在")
    void loginUnknownUserReturnsGenericError() {
        AuthException e = assertThrows(AuthException.class,
                () -> service.login(new LoginRequest("ghost", "whatever")));

        assertEquals(40004, e.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        verify(userMapper, never()).insert(any(UserAccount.class));
    }

    @Test
    @DisplayName("登录：密码错误返回 40004")
    void loginWrongPassword() {
        when(userMapper.findByUsername("alice")).thenReturn(account("alice", "correct-password", 1));

        AuthException e = assertThrows(AuthException.class,
                () -> service.login(new LoginRequest("alice", "wrong-password")));

        assertEquals(40004, e.getCode());
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    @DisplayName("登录：禁用账号返回 403/40005")
    void loginDisabledAccount() {
        when(userMapper.findByUsername("alice")).thenReturn(account("alice", "correct-password", 0));

        AuthException e = assertThrows(AuthException.class,
                () -> service.login(new LoginRequest("alice", "correct-password")));

        assertEquals(40005, e.getCode());
        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    @Test
    @DisplayName("登录成功签发 Token，并返回用户角色")
    void loginIssuesToken() {
        UserAccount user = account("alice", "correct-password", 1);
        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(tokenService.issue(user))
                .thenReturn(new TokenService.IssuedToken("raw-token", LocalDateTime.now().plusDays(1)));

        AuthResponse response = service.login(new LoginRequest("alice", "correct-password"));

        assertEquals("raw-token", response.token());
        assertEquals("alice", response.user().username());
        assertEquals("USER", response.user().role());
    }

    @Test
    @DisplayName("注册：密码只存 BCrypt 摘要，用户名重复返回 409/40006")
    void registerHashesPasswordAndRejectsDuplicate() {
        doAnswer(inv -> {
            UserAccount inserted = inv.getArgument(0);
            inserted.setId(5L);
            return 1;
        }).when(userMapper).insert(any(UserAccount.class));
        when(tokenService.issue(any(UserAccount.class)))
                .thenAnswer(inv -> new TokenService.IssuedToken(
                        "raw-token", LocalDateTime.now().plusDays(1)));

        service.register(new RegisterRequest("alice", "correct-password"));

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userMapper).insert(captor.capture());
        UserAccount stored = captor.getValue();
        assertNotEquals("correct-password", stored.getPasswordHash());
        assertTrue(encoder.matches("correct-password", stored.getPasswordHash()));
        assertEquals(UserAccount.ROLE_USER, stored.getRole());
        assertEquals(1, stored.getEnabled());

        when(userMapper.findByUsername("alice")).thenReturn(stored);
        AuthException e = assertThrows(AuthException.class,
                () -> service.register(new RegisterRequest("alice", "another-password")));
        assertEquals(40006, e.getCode());
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    @Test
    @DisplayName("注册：关闭注册时返回 403")
    void registerCanBeDisabled() {
        properties.setAllowRegistration(false);

        AuthException e = assertThrows(AuthException.class,
                () -> service.register(new RegisterRequest("alice", "correct-password")));

        assertEquals(40003, e.getCode());
        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    @Test
    @DisplayName("注册：UTF-8 超过 72 字节的密码在 BCrypt 前被拒绝")
    void registerRejectsPasswordOverBcryptLimit() {
        String longPassword = "密".repeat(25); // 75 bytes
        assertTrue(longPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72);

        AuthException e = assertThrows(AuthException.class,
                () -> service.register(new RegisterRequest("alice", longPassword)));

        assertEquals(10001, e.getCode());
        assertFalse(e.getStatus().is2xxSuccessful());
    }

    private UserAccount account(String username, String rawPassword, int enabled) {
        UserAccount account = new UserAccount();
        account.setId(7L);
        account.setUsername(username);
        account.setPasswordHash(encoder.encode(rawPassword));
        account.setRole(UserAccount.ROLE_USER);
        account.setEnabled(enabled);
        return account;
    }
}
