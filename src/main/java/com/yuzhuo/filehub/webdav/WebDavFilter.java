package com.yuzhuo.filehub.webdav;

import com.yuzhuo.filehub.auth.AuthException;
import com.yuzhuo.filehub.auth.AuthService;
import com.yuzhuo.filehub.auth.RateLimitProperties;
import com.yuzhuo.filehub.auth.RateLimiter;
import com.yuzhuo.filehub.auth.TokenService;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.dto.FileVO;
import com.yuzhuo.filehub.dto.FolderTreeResponse;
import com.yuzhuo.filehub.entity.FileFolder;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.FileFolderMapper;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.service.AuditService;
import com.yuzhuo.filehub.service.FileStorageService;
import com.yuzhuo.filehub.service.FolderService;
import com.yuzhuo.filehub.util.FileMediaTypeResolver;
import com.yuzhuo.filehub.util.PathUtil;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标准 WebDAV（RFC 4918 Class 1）处理器，挂载在 {@code /dav/*}。
 *
 * <p>以 Servlet Filter 形态拦截 {@code /dav} 与 {@code /dav/*}，既能接收扩展 HTTP 方法
 * （PROPFIND / MKCOL / MOVE / COPY）与任意大小流式 PUT，
 * 又不会额外引入第二个 Servlet 影响 Spring Security 单 DispatcherServlet 路由。
 * 当前仅声明 Class 1 能力，不伪造未持久化校验的 Class 2 {@code LOCK / UNLOCK}。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class WebDavFilter extends HttpFilter {

    private static final String ALLOW_METHODS =
            "OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY";
    private static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final AuthService authService;
    private final TokenService tokenService;
    private final FileStorageService fileStorageService;
    private final FolderService folderService;
    private final FileInfoMapper fileInfoMapper;
    private final FileFolderMapper fileFolderMapper;
    private final AuditService auditService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;
    private final MeterRegistry meters;

    private final ConcurrentHashMap<String, Instant> authLockedIps = new ConcurrentHashMap<>();

    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        String uri = req.getRequestURI();
        String prefix = req.getContextPath() + "/dav";
        if (!uri.equals(prefix) && !uri.startsWith(prefix + "/")) {
            chain.doFilter(req, resp);
            return;
        }

        String method = req.getMethod().toUpperCase();
        if ("OPTIONS".equals(method)) {
            handleOptions(resp);
            return;
        }

        String ip = clientIp(req);
        if (isIpAuthLocked(ip, resp)) {
            return;
        }

        UserPrincipal user = authenticate(req, resp, ip);
        if (user == null) {
            return;
        }

        if (rateLimitProperties.isEnabled() && rateLimitProperties.getWebdavPerMinute() > 0) {
            RateLimiter.Decision decision = rateLimiter.tryAcquire(
                    "webdav:u:" + user.id(), rateLimitProperties.getWebdavPerMinute(), 60);
            if (!decision.allowed()) {
                meters.counter("filehub.rate_limit.rejected", "scope", "webdav").increment();
                sendRateLimited(resp, decision.retryAfterSeconds(), "WebDAV 请求过于频繁，请稍后再试");
                return;
            }
        }

        String relPath;
        try {
            relPath = extractDavPath(req);
        } catch (BizException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }

        try {
            switch (method) {
                case "PROPFIND" -> handlePropfind(req, resp, user, relPath);
                case "GET" -> handleGetOrHead(req, resp, user, relPath, false);
                case "HEAD" -> handleGetOrHead(req, resp, user, relPath, true);
                case "PUT" -> handlePut(req, resp, user, relPath);
                case "MKCOL" -> handleMkcol(req, resp, user, relPath);
                case "DELETE" -> handleDelete(req, resp, user, relPath);
                case "MOVE" -> handleMove(req, resp, user, relPath);
                case "COPY" -> handleCopy(req, resp, user, relPath);
                default -> {
                    resp.setHeader("Allow", ALLOW_METHODS);
                    resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
            }
        } catch (BizException e) {
            int status = switch (e.getCode()) {
                case 10005 -> HttpServletResponse.SC_NOT_FOUND;
                case 41001 -> 507;
                default -> HttpServletResponse.SC_BAD_REQUEST;
            };
            resp.sendError(status, e.getMessage());
        }
    }

    private void handleOptions(HttpServletResponse resp) {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setHeader("DAV", "1");
        resp.setHeader("MS-Author-Via", "DAV");
        resp.setHeader("Allow", ALLOW_METHODS);
    }

    private void handlePropfind(HttpServletRequest req, HttpServletResponse resp,
                                UserPrincipal user, String relPath) throws IOException {
        String depthHeader = req.getHeader("Depth");
        int depth = "0".equals(depthHeader) ? 0 : 1;

        StringBuilder xml = new StringBuilder(1024);
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        xml.append("<D:multistatus xmlns:D=\"DAV:\">\n");

        if (relPath.isEmpty()) {
            appendFolderResponse(xml, req, "", "FileHub", LocalDateTime.now());
            if (depth > 0) {
                appendFolderChildren(xml, req, user, "");
            }
        } else {
            FileInfo file = findUserFile(user, relPath);
            if (file != null) {
                appendFileResponse(xml, req, file);
            } else if (folderExists(user, relPath)) {
                String name = relPath.contains("/")
                        ? relPath.substring(relPath.lastIndexOf('/') + 1)
                        : relPath;
                appendFolderResponse(xml, req, relPath, name, LocalDateTime.now());
                if (depth > 0) {
                    appendFolderChildren(xml, req, user, relPath);
                }
            } else {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        }

        xml.append("</D:multistatus>\n");
        byte[] bytes = xml.toString().getBytes(StandardCharsets.UTF_8);
        resp.setStatus(207);
        resp.setContentType("application/xml; charset=utf-8");
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private void appendFolderChildren(StringBuilder xml, HttpServletRequest req,
                                      UserPrincipal user, String dir) {
        FolderTreeResponse tree = folderService.tree(dir, null, "name_asc", user, null);
        for (FolderTreeResponse.FolderItem folder : tree.folders()) {
            appendFolderResponse(xml, req, folder.path(), folder.name(), folder.updateTime());
        }
        for (FileVO file : tree.files()) {
            String path = PathUtil.effectivePath(file.fileName(), file.relativePath());
            appendFileVoResponse(xml, req, path, file);
        }
    }

    private void appendFolderResponse(StringBuilder xml, HttpServletRequest req,
                                      String path, String displayName, LocalDateTime updated) {
        String href = buildDavHref(req, path, true);
        String date = formatHttpDate(updated);
        xml.append("  <D:response>\n");
        xml.append("    <D:href>").append(escapeXml(href)).append("</D:href>\n");
        xml.append("    <D:propstat>\n");
        xml.append("      <D:prop>\n");
        xml.append("        <D:displayname>").append(escapeXml(displayName)).append("</D:displayname>\n");
        xml.append("        <D:resourcetype><D:collection/></D:resourcetype>\n");
        xml.append("        <D:getlastmodified>").append(date).append("</D:getlastmodified>\n");
        xml.append("      </D:prop>\n");
        xml.append("      <D:status>HTTP/1.1 200 OK</D:status>\n");
        xml.append("    </D:propstat>\n");
        xml.append("  </D:response>\n");
    }

    private void appendFileResponse(StringBuilder xml, HttpServletRequest req, FileInfo file) {
        appendFileVoResponse(xml, req, PathUtil.effectivePath(file.getFileName(), file.getRelativePath()),
                FileVO.from(file));
    }

    private void appendFileVoResponse(StringBuilder xml, HttpServletRequest req,
                                      String path, FileVO file) {
        String href = buildDavHref(req, path, false);
        String date = formatHttpDate(file.createTime());
        String contentType = FileMediaTypeResolver.resolveForInline(file.fileName()).toString();
        xml.append("  <D:response>\n");
        xml.append("    <D:href>").append(escapeXml(href)).append("</D:href>\n");
        xml.append("    <D:propstat>\n");
        xml.append("      <D:prop>\n");
        xml.append("        <D:displayname>").append(escapeXml(file.fileName())).append("</D:displayname>\n");
        xml.append("        <D:resourcetype/>\n");
        xml.append("        <D:getcontentlength>").append(file.fileSize()).append("</D:getcontentlength>\n");
        xml.append("        <D:getcontenttype>").append(escapeXml(contentType)).append("</D:getcontenttype>\n");
        xml.append("        <D:getetag>\"").append(escapeXml(file.fileHash())).append("\"</D:getetag>\n");
        xml.append("        <D:getlastmodified>").append(date).append("</D:getlastmodified>\n");
        xml.append("      </D:prop>\n");
        xml.append("      <D:status>HTTP/1.1 200 OK</D:status>\n");
        xml.append("    </D:propstat>\n");
        xml.append("  </D:response>\n");
    }

    private void handleGetOrHead(HttpServletRequest req, HttpServletResponse resp,
                                 UserPrincipal user, String relPath, boolean headOnly) throws IOException {
        if (relPath.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        FileInfo file = findUserFile(user, relPath);
        if (file == null) {
            if (folderExists(user, relPath)) {
                resp.setStatus(HttpServletResponse.SC_OK);
                return;
            }
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        fileStorageService.requireReadableInfo(file.getFileId(), user);

        long size = file.getFileSize();
        String contentType = FileMediaTypeResolver.resolveForInline(file.getFileName()).toString();
        resp.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        resp.setHeader(HttpHeaders.ETAG, "\"" + file.getFileHash() + "\"");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        if (FileMediaTypeResolver.needsSandboxCsp(file.getFileName())) {
            resp.setHeader("Content-Security-Policy", "sandbox");
        }
        resp.setContentType(contentType);

        String rangeHeader = req.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            List<HttpRange> ranges;
            try {
                ranges = HttpRange.parseRanges(rangeHeader);
            } catch (IllegalArgumentException e) {
                resp.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                resp.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                return;
            }
            if (ranges.size() == 1) {
                long start = ranges.get(0).getRangeStart(size);
                long end = ranges.get(0).getRangeEnd(size);
                if (start < 0 || start >= size || end < start || end >= size) {
                    resp.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    resp.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                    return;
                }
                long len = end - start + 1;
                resp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                resp.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
                resp.setContentLengthLong(len);
                if (!headOnly) {
                    try (InputStream in = fileStorageService.openRangeForRead(file, start, len)) {
                        copyBounded(in, resp.getOutputStream(), len);
                    }
                }
                return;
            }
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentLengthLong(size);
        if (!headOnly) {
            try (InputStream in = fileStorageService.openForRead(file)) {
                in.transferTo(resp.getOutputStream());
            }
        }
    }

    private void handlePut(HttpServletRequest req, HttpServletResponse resp,
                           UserPrincipal user, String relPath) throws IOException {
        if (relPath.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "不能向根目录直接 PUT");
            return;
        }
        String parentDir = PathUtil.parentDir(relPath);
        String fileName = relPath.contains("/")
                ? relPath.substring(relPath.lastIndexOf('/') + 1)
                : relPath;
        PathUtil.requireSafeName(fileName);

        boolean existed = findUserFile(user, relPath) != null;
        if (!parentDir.isEmpty()) {
            folderService.ensureFoldersExist(parentDir, user.id());
        }
        String storedRelPath = PathUtil.toFileRelativePath(parentDir, fileName);
        FileVO saved = fileStorageService.storeStream(fileName, storedRelPath, req.getInputStream(), user);
        auditService.record(user, "WEBDAV_PUT", "FILE", saved.fileId(),
                relPath, req.getRemoteAddr());

        resp.setHeader(HttpHeaders.ETAG, "\"" + saved.fileHash() + "\"");
        resp.setStatus(existed ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_CREATED);
    }

    private void handleMkcol(HttpServletRequest req, HttpServletResponse resp,
                             UserPrincipal user, String relPath) throws IOException {
        if (relPath.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        if (findUserFile(user, relPath) != null || folderExists(user, relPath)) {
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "目标已存在");
            return;
        }
        folderService.createFolder(relPath, user);
        auditService.record(user, "WEBDAV_MKCOL", "FOLDER", relPath, null, req.getRemoteAddr());
        resp.setStatus(HttpServletResponse.SC_CREATED);
    }

    private void handleDelete(HttpServletRequest req, HttpServletResponse resp,
                              UserPrincipal user, String relPath) throws IOException {
        if (relPath.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        FileInfo file = findUserFile(user, relPath);
        if (file != null) {
            fileStorageService.moveToTrash(file.getFileId(), user);
            auditService.record(user, "WEBDAV_DELETE", "FILE", file.getFileId(),
                    relPath, req.getRemoteAddr());
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if (folderExists(user, relPath)) {
            int trashed = folderService.trashFolder(relPath, user);
            auditService.record(user, "WEBDAV_DELETE", "FOLDER", relPath,
                    "files=" + trashed, req.getRemoteAddr());
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private void handleMove(HttpServletRequest req, HttpServletResponse resp,
                            UserPrincipal user, String srcPath) throws IOException {
        String dstPath = extractDestinationPath(req);
        if (dstPath == null || dstPath.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "缺少有效的 Destination 请求头");
            return;
        }
        boolean noOverwrite = "F".equalsIgnoreCase(req.getHeader("Overwrite"));
        FileInfo srcFile = findUserFile(user, srcPath);
        if (srcFile != null) {
            String dstDir = PathUtil.parentDir(dstPath);
            String dstName = dstPath.contains("/")
                    ? dstPath.substring(dstPath.lastIndexOf('/') + 1)
                    : dstPath;
            FileInfo existingDst = findUserFile(user, dstPath);
            if (existingDst != null && !existingDst.getFileId().equals(srcFile.getFileId())) {
                if (noOverwrite) {
                    resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
                    return;
                }
                fileStorageService.moveToTrash(existingDst.getFileId(), user);
            }
            folderService.moveFile(srcFile.getFileId(), dstDir, dstName, user);
            auditService.record(user, "WEBDAV_MOVE", "FILE", srcFile.getFileId(),
                    srcPath + " -> " + dstPath, req.getRemoteAddr());
            resp.setStatus(existingDst != null ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_CREATED);
            return;
        }
        if (folderExists(user, srcPath)) {
            folderService.renameOrMoveFolder(srcPath, dstPath, user);
            auditService.record(user, "WEBDAV_MOVE", "FOLDER", srcPath,
                    "-> " + dstPath, req.getRemoteAddr());
            resp.setStatus(HttpServletResponse.SC_CREATED);
            return;
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private void handleCopy(HttpServletRequest req, HttpServletResponse resp,
                            UserPrincipal user, String srcPath) throws IOException {
        String dstPath = extractDestinationPath(req);
        if (dstPath == null || dstPath.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "缺少有效的 Destination 请求头");
            return;
        }
        FileInfo srcFile = findUserFile(user, srcPath);
        if (srcFile == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        boolean noOverwrite = "F".equalsIgnoreCase(req.getHeader("Overwrite"));
        FileInfo existingDst = findUserFile(user, dstPath);
        if (existingDst != null && noOverwrite) {
            resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
            return;
        }
        String dstDir = PathUtil.parentDir(dstPath);
        String dstName = dstPath.contains("/")
                ? dstPath.substring(dstPath.lastIndexOf('/') + 1)
                : dstPath;
        FileVO copy = fileStorageService.createSharedCopy(srcFile, user);
        folderService.moveFile(copy.fileId(), dstDir, dstName, user);
        if (existingDst != null) {
            fileStorageService.moveToTrash(existingDst.getFileId(), user);
        }
        auditService.record(user, "WEBDAV_COPY", "FILE", copy.fileId(),
                srcPath + " -> " + dstPath, req.getRemoteAddr());
        resp.setStatus(existingDst != null ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_CREATED);
    }

    private UserPrincipal authenticate(HttpServletRequest req, HttpServletResponse resp, String ip)
            throws IOException {
        String auth = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null) {
            if (auth.regionMatches(true, 0, "Basic ", 0, 6)) {
                try {
                    String decoded = new String(
                            Base64.getDecoder().decode(auth.substring(6).trim()),
                            StandardCharsets.UTF_8);
                    int colon = decoded.indexOf(':');
                    if (colon > 0) {
                        String username = decoded.substring(0, colon);
                        String password = decoded.substring(colon + 1);
                        UserAccount account = authService.verifyCredentials(username, password);
                        return UserPrincipal.from(account);
                    }
                } catch (IllegalArgumentException | AuthException ignored) {
                    // 下面统一计入失败限流并返回 429 或 401
                }
            } else if (auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                UserPrincipal principal = tokenService.authenticate(auth.substring(7).trim());
                if (principal != null) {
                    return principal;
                }
            }

            if (rateLimitProperties.isEnabled() && rateLimitProperties.getWebdavAuthFailPerMinute() > 0) {
                RateLimiter.Decision d = rateLimiter.tryAcquire(
                        "webdav-fail:ip:" + ip, rateLimitProperties.getWebdavAuthFailPerMinute(), 60);
                if (!d.allowed()) {
                    long retryAfter = Math.max(1L, d.retryAfterSeconds());
                    if (authLockedIps.size() > 4096) {
                        authLockedIps.clear();
                    }
                    authLockedIps.put(ip, Instant.now().plusSeconds(retryAfter));
                    meters.counter("filehub.rate_limit.rejected", "scope", "webdav-auth").increment();
                    sendRateLimited(resp, retryAfter, "WebDAV 认证失败次数过多，请稍后再试");
                    return null;
                }
            }
        }
        resp.setHeader("WWW-Authenticate", "Basic realm=\"FileHub WebDAV\"");
        resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "需要 FileHub 账号密码");
        return null;
    }

    private boolean isIpAuthLocked(String ip, HttpServletResponse resp) throws IOException {
        if (!rateLimitProperties.isEnabled()) {
            return false;
        }
        Instant until = authLockedIps.get(ip);
        if (until == null) {
            return false;
        }
        long remaining = java.time.Duration.between(Instant.now(), until).getSeconds();
        if (remaining <= 0) {
            authLockedIps.remove(ip, until);
            return false;
        }
        meters.counter("filehub.rate_limit.rejected", "scope", "webdav-auth").increment();
        sendRateLimited(resp, remaining, "WebDAV 认证失败次数过多，请稍后再试");
        return true;
    }

    private void sendRateLimited(HttpServletResponse resp, long retryAfterSeconds, String message)
            throws IOException {
        resp.setHeader("Retry-After", String.valueOf(Math.max(1L, retryAfterSeconds)));
        resp.sendError(429, message);
    }

    private String clientIp(HttpServletRequest request) {
        if (rateLimitProperties.isTrustForwardedHeaders()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = comma >= 0 ? forwarded.substring(0, comma) : forwarded;
                if (!first.isBlank()) {
                    return first.trim();
                }
            }
        }
        return request.getRemoteAddr();
    }

    private FileInfo findUserFile(UserPrincipal user, String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return null;
        }
        boolean isRootFile = !relPath.contains("/");
        List<FileInfo> candidates = fileInfoMapper.listDoneByOwner(user.id(), user.isAdmin());
        for (FileInfo info : candidates) {
            String storedRel = info.getRelativePath() == null ? "" : info.getRelativePath();
            if (isRootFile) {
                if (storedRel.isEmpty() && relPath.equals(info.getFileName())) {
                    return info;
                }
                if (relPath.equals(storedRel)) {
                    return info;
                }
            } else if (relPath.equals(storedRel)) {
                return info;
            }
        }
        return null;
    }

    private boolean folderExists(UserPrincipal user, String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) {
            return true;
        }
        String hash = PathUtil.sha256Hex(dirPath);
        if (fileFolderMapper.findByOwnerAndHash(user.id(), hash) != null) {
            return true;
        }
        String prefix = dirPath + "/";
        for (FileFolder f : fileFolderMapper.listByOwner(user.id())) {
            if (f.getFolderPath() != null && (f.getFolderPath().equals(dirPath) || f.getFolderPath().startsWith(prefix))) {
                return true;
            }
        }
        for (FileInfo info : fileInfoMapper.listDoneByOwner(user.id(), user.isAdmin())) {
            if (info.getRelativePath() != null && info.getRelativePath().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String extractDavPath(HttpServletRequest req) {
        String prefix = req.getContextPath() + "/dav";
        String uri = req.getRequestURI();
        String raw = uri.startsWith(prefix) ? uri.substring(prefix.length()) : (req.getPathInfo() == null ? "" : req.getPathInfo());
        String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        return PathUtil.normalizeDir(decoded);
    }

    private static String extractDestinationPath(HttpServletRequest req) {
        String dest = req.getHeader("Destination");
        if (dest == null || dest.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(dest.trim());
            String rawPath = uri.getRawPath();
            String prefix = req.getContextPath() + "/dav";
            if (rawPath == null || (!rawPath.equals(prefix) && !rawPath.startsWith(prefix + "/"))) {
                return null;
            }
            String sub = URLDecoder.decode(rawPath.substring(prefix.length()), StandardCharsets.UTF_8);
            return PathUtil.normalizeDir(sub);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String buildDavHref(HttpServletRequest req, String path, boolean folder) {
        String base = req.getContextPath() + "/dav/";
        if (path == null || path.isEmpty()) {
            return base;
        }
        String[] segments = path.split("/");
        StringBuilder sb = new StringBuilder(base);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        if (folder) {
            sb.append('/');
        }
        return sb.toString();
    }

    private static String formatHttpDate(LocalDateTime time) {
        LocalDateTime safe = time == null ? LocalDateTime.now() : time;
        return RFC_1123.format(safe.atOffset(ZoneOffset.UTC));
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static void copyBounded(InputStream in, OutputStream out, long len) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long remaining = len;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
    }
}
