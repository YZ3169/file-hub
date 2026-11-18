package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.util.HashUtil;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 不透明随机 Token 的生成与摘要。
 *
 * <p>32 字节 SecureRandom → Base64URL（43 字符），数据库只保存 SHA-256。
 * 即使数据库泄露，也无法从哈希反推可用的 Bearer Token。</p>
 */
public final class OpaqueToken {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OpaqueToken() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    public static String hash(String raw) {
        return HashUtil.sha256Hex(raw.getBytes(StandardCharsets.UTF_8));
    }
}
