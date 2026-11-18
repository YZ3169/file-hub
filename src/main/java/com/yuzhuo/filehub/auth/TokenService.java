package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.entity.UserSession;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import com.yuzhuo.filehub.mapper.UserSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 会话 Token 的签发、校验和撤销。
 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final UserSessionMapper sessionMapper;

    private final UserAccountMapper userMapper;

    private final AuthProperties properties;

    public record IssuedToken(String token, LocalDateTime expiresAt) {
    }

    public IssuedToken issue(UserAccount user) {
        String raw = OpaqueToken.generate();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusHours(Math.max(1, properties.getTokenTtlHours()));

        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setTokenHash(OpaqueToken.hash(raw));
        session.setExpiresAt(expiresAt);
        session.setCreatedAt(now);
        sessionMapper.insert(session);
        return new IssuedToken(raw, expiresAt);
    }

    /**
     * 校验原始 Token。无效/过期/账号被禁用都返回 null，调用方统一按 401 处理。
     */
    public UserPrincipal authenticate(String raw) {
        if (raw == null || raw.length() < 20 || raw.length() > 256) {
            return null;
        }
        UserSession session = sessionMapper.findActive(
                OpaqueToken.hash(raw), LocalDateTime.now());
        if (session == null) {
            return null;
        }
        UserAccount user = userMapper.selectById(session.getUserId());
        if (user == null || !user.isActive()) {
            return null;
        }
        return UserPrincipal.from(user);
    }

    public void revoke(String raw) {
        if (raw != null && !raw.isBlank()) {
            sessionMapper.deleteByTokenHash(OpaqueToken.hash(raw));
        }
    }

    /** 从 Authorization 头取出 Bearer Token；不是 Bearer 方案时返回 null */
    public static String extractBearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return authorization.substring(prefix.length()).trim();
    }
}
