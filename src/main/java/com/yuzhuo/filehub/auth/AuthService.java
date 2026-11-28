package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.dto.AuthResponse;
import com.yuzhuo.filehub.dto.ChangePasswordRequest;
import com.yuzhuo.filehub.dto.LoginRequest;
import com.yuzhuo.filehub.dto.RegisterRequest;
import com.yuzhuo.filehub.dto.UserVO;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import com.yuzhuo.filehub.service.AuditService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    /** BCrypt 只处理前 72 字节，超长密码必须在编码前拦下，避免「同一前缀都能登录」。 */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserAccountMapper userMapper;

    private final TokenService tokenService;

    private final PasswordEncoder passwordEncoder;

    private final AuthProperties properties;

    private StorageProperties storageProperties;

    private AuditService auditService;

    /** 用户不存在时也跑一次 BCrypt，避免通过响应时间枚举用户名。 */
    private String dummyHash;

    @Autowired(required = false)
    public void setStorageProperties(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    @Autowired(required = false)
    public void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    @PostConstruct
    void initDummyHash() {
        dummyHash = passwordEncoder.encode("file-hub-dummy-password-for-timing");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (!properties.isAllowRegistration()) {
            throw AuthException.forbidden("当前未开放注册");
        }
        String username = request.username().trim();
        String password = request.password();
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new AuthException(10001, "密码过长（UTF-8 不能超过 72 字节）", HttpStatus.BAD_REQUEST);
        }
        if (userMapper.findByUsername(username) != null) {
            throw new AuthException(40006, "用户名已存在", HttpStatus.CONFLICT);
        }

        LocalDateTime now = LocalDateTime.now();
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(password));
        // 桌面版单机使用：第一个注册的人就是管理员，否则完整性扫描等管理功能无人可用。
        // 服务器模式默认关闭这个开关，仍由 FILE_HUB_ADMIN_PASSWORD 初始化管理员。
        boolean firstUser = properties.isFirstUserAdmin() && userMapper.selectCount(null) == 0;
        account.setRole(firstUser ? UserAccount.ROLE_ADMIN : UserAccount.ROLE_USER);
        account.setEnabled(1);
        account.setStorageQuotaBytes(storageProperties == null ? -1L : storageProperties.getQuota().getDefaultBytes());
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        try {
            userMapper.insert(account);
        } catch (DuplicateKeyException e) {
            // 并发注册同名用户时唯一索引兜底
            throw new AuthException(40006, "用户名已存在", HttpStatus.CONFLICT);
        }
        log.info("用户注册成功 id={} username={}", account.getId(), username);
        if (auditService != null) {
            auditService.record(account.getId(), account.getUsername(), "REGISTER",
                    "USER", String.valueOf(account.getId()), "role=" + account.getRole(), null);
        }
        return issue(account);
    }

    public AuthResponse login(LoginRequest request) {
        UserAccount account = verifyCredentials(request.username(), request.password());
        log.info("用户登录成功 id={} username={}", account.getId(), account.getUsername());
        if (auditService != null) {
            auditService.record(account.getId(), account.getUsername(), "LOGIN",
                    "USER", String.valueOf(account.getId()), null, null);
        }
        return issue(account);
    }

    /** 校验账号密码与可用状态（登录和 WebDAV HTTP Basic 复用） */
    public UserAccount verifyCredentials(String rawUsername, String rawPassword) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        String password = rawPassword == null ? "" : rawPassword;
        UserAccount account = userMapper.findByUsername(username);
        boolean tooLong = password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
        if (account == null) {
            passwordEncoder.matches(password, dummyHash);
            throw invalidCredentials();
        }
        if (tooLong || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (!account.isActive()) {
            throw new AuthException(40005, "账号已被禁用", HttpStatus.FORBIDDEN);
        }
        return account;
    }

    public void changePassword(UserPrincipal principal, ChangePasswordRequest req) {
        changePassword(principal, req, null);
    }

    @Transactional
    public void changePassword(UserPrincipal principal, ChangePasswordRequest req, String currentRawToken) {
        UserAccount account = userMapper.selectById(principal.id());
        if (account == null || !account.isActive()) {
            throw AuthException.unauthorized("账号不存在或已被禁用");
        }
        if (!passwordEncoder.matches(req.currentPassword(), account.getPasswordHash())) {
            throw new AuthException(40004, "当前密码不正确", HttpStatus.BAD_REQUEST);
        }
        String newPwd = req.newPassword();
        if (newPwd.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new AuthException(10001, "密码过长（UTF-8 不能超过 72 字节）", HttpStatus.BAD_REQUEST);
        }
        if (req.currentPassword().equals(newPwd)) {
            throw new AuthException(10001, "新密码不能与当前密码相同", HttpStatus.BAD_REQUEST);
        }
        account.setPasswordHash(passwordEncoder.encode(newPwd));
        account.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(account);
        tokenService.revokeOthersForUser(principal.id(), currentRawToken);
        if (auditService != null) {
            auditService.record(principal, "PASSWORD_CHANGE", "USER",
                    String.valueOf(principal.id()), null, null);
        }
        log.info("用户修改密码成功 id={}", principal.id());
    }

    public UserVO me(UserPrincipal principal) {
        UserAccount account = userMapper.selectById(principal.id());
        if (account == null || !account.isActive()) {
            throw AuthException.unauthorized("账号不存在或已被禁用");
        }
        return UserVO.from(account);
    }

    public void logout(String authorization) {
        tokenService.revoke(TokenService.extractBearer(authorization));
    }

    private AuthResponse issue(UserAccount account) {
        TokenService.IssuedToken token = tokenService.issue(account);
        return new AuthResponse(token.token(), token.expiresAt(), UserVO.from(account));
    }

    private AuthException invalidCredentials() {
        return new AuthException(40004, "账号或密码错误", HttpStatus.UNAUTHORIZED);
    }
}
