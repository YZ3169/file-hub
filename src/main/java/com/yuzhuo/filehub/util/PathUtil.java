package com.yuzhuo.filehub.util;

import com.yuzhuo.filehub.common.BizException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 文件夹与文件相对路径校验工具。
 * 统一用 {@code /} 分隔，禁止前导/尾随斜杠、反斜杠、盘符、{@code .}、{@code ..} 与控制字符。
 */
public final class PathUtil {

    public static final int MAX_PATH_LENGTH = 4096;
    public static final int MAX_NAME_LENGTH = 255;

    private PathUtil() {
    }

    /** 校验并规范化文件夹路径；根目录返回空字符串 "" */
    public static String normalizeDir(String rawDir) {
        if (rawDir == null) {
            return "";
        }
        String trimmed = rawDir.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return "";
        }
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        validateSegments(trimmed, MAX_PATH_LENGTH, "目录路径");
        return trimmed;
    }

    /** 要求非空的文件夹路径 */
    public static String requireNonEmptyDir(String rawDir) {
        String normalized = normalizeDir(rawDir);
        if (normalized.isEmpty()) {
            throw new BizException(10001, "目录路径不能为空");
        }
        return normalized;
    }

    /** 校验单个文件名/目录名（不允许包含斜杠） */
    public static String requireSafeName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new BizException(10001, "名称不能为空");
        }
        String name = rawName.trim();
        if (name.length() > MAX_NAME_LENGTH
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || ".".equals(name)
                || "..".equals(name)
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new BizException(10001, "名称包含非法字符或超长（最多 255 字符）");
        }
        return name;
    }

    /** 计算文件的父目录路径；根目录文件返回 "" */
    public static String parentDir(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return "";
        }
        int slash = relativePath.lastIndexOf('/');
        return slash <= 0 ? "" : relativePath.substring(0, slash);
    }

    /** 拼装写入 file_info.relative_path 的值：根目录文件仍约定为空字符串 "" */
    public static String toFileRelativePath(String dir, String fileName) {
        String cleanDir = normalizeDir(dir);
        String cleanName = requireSafeName(fileName);
        return cleanDir.isEmpty() ? "" : cleanDir + "/" + cleanName;
    }

    /** 文件在目录树/Zip 里的完整展示路径 */
    public static String effectivePath(String fileName, String relativePath) {
        if (relativePath != null && !relativePath.isEmpty()) {
            return relativePath;
        }
        return fileName;
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void validateSegments(String path, int maxLen, String label) {
        if (path.length() > maxLen
                || path.indexOf('\\') >= 0
                || path.matches("^[a-zA-Z]:.*")
                || path.codePoints().anyMatch(Character::isISOControl)) {
            throw new BizException(10001, label + "格式非法或长度超过 " + maxLen);
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.length() > MAX_NAME_LENGTH) {
                throw new BizException(10001, label + "不能包含空段、.、.. 或超长段");
            }
        }
    }
}
