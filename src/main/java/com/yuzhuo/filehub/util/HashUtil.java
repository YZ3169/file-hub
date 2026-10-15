package com.yuzhuo.filehub.util;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 哈希工具。
 *
 * <p>用 SHA-256 而不是 MD5：MD5 早就有实用化的碰撞攻击，用它做「内容相同」的判据
 * 意味着别人可以构造两个内容不同、哈希相同的文件来骗过秒传。
 * SHA-256 的代价是摘要长一倍（64 字符 vs 32），换来的是抗碰撞。</p>
 */
public final class HashUtil {

    private HashUtil() {
    }

    /** 合并时要一边写盘一边算摘要，所以把 MessageDigest 本身暴露出去 */
    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static MessageDigest newDigest() {
        return sha256();
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(newDigest().digest(data));
    }

    /**
     * 流式摘要。大文件绝不能整个读进内存——一个 2GB 的文件会直接 OOM，
     * 而流式只需要一个 8KB 的缓冲区。
     */
    public static String sha256Hex(InputStream in) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
