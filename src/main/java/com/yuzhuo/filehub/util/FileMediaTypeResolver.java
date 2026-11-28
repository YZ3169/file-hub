package com.yuzhuo.filehub.util;

import com.yuzhuo.filehub.common.BizException;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 预览/WebDAV 用的安全媒体类型解析。
 *
 * <p>HTML / XML / JS 等脚本可执行类型在 {@code inline=true} 预览时一律降级为
 * {@code text/plain; charset=UTF-8}；SVG 返回 {@code image/svg+xml} 但由控制器附带
 * {@code Content-Security-Policy: sandbox}，防止同源存储型 XSS 窃取 Token。</p>
 */
public final class FileMediaTypeResolver {

    public static final MediaType TEXT_PLAIN_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private static final Map<String, MediaType> SAFE_MEDIA_TYPES = Map.ofEntries(
            Map.entry("png", MediaType.IMAGE_PNG),
            Map.entry("jpg", MediaType.IMAGE_JPEG),
            Map.entry("jpeg", MediaType.IMAGE_JPEG),
            Map.entry("gif", MediaType.IMAGE_GIF),
            Map.entry("webp", MediaType.parseMediaType("image/webp")),
            Map.entry("bmp", MediaType.parseMediaType("image/bmp")),
            Map.entry("ico", MediaType.parseMediaType("image/x-icon")),
            Map.entry("svg", MediaType.parseMediaType("image/svg+xml")),

            Map.entry("mp4", MediaType.parseMediaType("video/mp4")),
            Map.entry("m4v", MediaType.parseMediaType("video/mp4")),
            Map.entry("webm", MediaType.parseMediaType("video/webm")),
            Map.entry("ogv", MediaType.parseMediaType("video/ogg")),
            Map.entry("mov", MediaType.parseMediaType("video/quicktime")),

            Map.entry("mp3", MediaType.parseMediaType("audio/mpeg")),
            Map.entry("wav", MediaType.parseMediaType("audio/wav")),
            Map.entry("ogg", MediaType.parseMediaType("audio/ogg")),
            Map.entry("flac", MediaType.parseMediaType("audio/flac")),
            Map.entry("m4a", MediaType.parseMediaType("audio/mp4")),
            Map.entry("aac", MediaType.parseMediaType("audio/aac")),

            Map.entry("pdf", MediaType.APPLICATION_PDF)
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "log", "csv", "tsv",
            "json", "yml", "yaml", "toml", "ini", "conf", "properties", "env",
            "xml", "html", "htm", "css", "scss", "less",
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue",
            "java", "kt", "kts", "py", "go", "rs", "c", "h", "cpp", "hpp", "cs",
            "sh", "bash", "zsh", "bat", "cmd", "ps1", "sql", "gradle", "dockerfile"
    );

    private FileMediaTypeResolver() {
    }

    public static MediaType resolveForInline(String fileName) {
        String ext = extension(fileName);
        if (ext.isEmpty()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        MediaType mapped = SAFE_MEDIA_TYPES.get(ext);
        if (mapped != null) {
            return mapped;
        }
        if (TEXT_EXTENSIONS.contains(ext) || isSpecialTextName(fileName)) {
            return TEXT_PLAIN_UTF8;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    public static boolean isSafeInline(String fileName) {
        return !MediaType.APPLICATION_OCTET_STREAM.equals(resolveForInline(fileName));
    }

    public static boolean needsSandboxCsp(String fileName) {
        return "svg".equals(extension(fileName));
    }

    public static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static final Map<String, Set<String>> CATEGORY_EXTENSIONS = Map.of(
            "image", Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "avif", "tif", "tiff"),
            "video", Set.of("mp4", "m4v", "webm", "ogv", "mov", "mkv", "avi", "flv", "wmv"),
            "audio", Set.of("mp3", "wav", "ogg", "flac", "m4a", "aac", "wma", "opus"),
            "document", Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md",
                    "markdown", "csv", "tsv", "rtf", "odt", "epub"),
            "archive", Set.of("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst"),
            "code", Set.of("js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "java", "kt", "kts",
                    "py", "go", "rs", "c", "h", "cpp", "hpp", "cs", "sh", "bash", "zsh",
                    "bat", "cmd", "ps1", "sql", "html", "htm", "css", "scss", "less",
                    "json", "yml", "yaml", "xml", "toml", "ini", "conf", "properties",
                    "env", "gradle", "dockerfile")
    );

    public static Set<String> extensionsForCategory(String rawCategory) {
        if (rawCategory == null || rawCategory.isBlank()) {
            return Set.of();
        }
        String key = rawCategory.trim().toLowerCase(Locale.ROOT);
        Set<String> exts = CATEGORY_EXTENSIONS.get(key);
        if (exts == null) {
            throw new BizException(10008, "不支持的文件分类：" + rawCategory);
        }
        return exts;
    }

    public static boolean matchesCategory(String fileName, String rawCategory) {
        if (rawCategory == null || rawCategory.isBlank()) {
            return true;
        }
        String key = rawCategory.trim().toLowerCase(Locale.ROOT);
        Set<String> exts = extensionsForCategory(key);
        String ext = extension(fileName);
        if (!ext.isEmpty() && exts.contains(ext)) {
            return true;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if ("document".equals(key)) {
            return "readme".equals(lower) || "license".equals(lower);
        }
        if ("code".equals(key)) {
            return "dockerfile".equals(lower) || "makefile".equals(lower);
        }
        return false;
    }

    private static boolean isSpecialTextName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.equals("readme") || lower.equals("license")
                || lower.equals("dockerfile") || lower.equals("makefile");
    }
}
