package com.yuzhuo.filehub.controller;

import com.yuzhuo.filehub.auth.AuthException;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.dto.BatchFileIdsRequest;
import com.yuzhuo.filehub.dto.BatchMoveRequest;
import com.yuzhuo.filehub.dto.BatchOpResponse;
import com.yuzhuo.filehub.dto.CreateFolderRequest;
import com.yuzhuo.filehub.dto.FileVO;
import com.yuzhuo.filehub.dto.FolderTreeResponse;
import com.yuzhuo.filehub.dto.FolderTreeResponse.FolderItem;
import com.yuzhuo.filehub.dto.MoveFileRequest;
import com.yuzhuo.filehub.dto.MoveFolderRequest;
import com.yuzhuo.filehub.dto.ZipTicketRequest;
import com.yuzhuo.filehub.dto.ZipTicketResponse;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.service.AuditService;
import com.yuzhuo.filehub.service.FileStorageService;
import com.yuzhuo.filehub.service.FolderService;
import com.yuzhuo.filehub.service.ZipTicketService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@Tag(name = "目录与批量操作", description = "文件夹树 / 移动重命名 / 批量操作 / 流式 Zip 下载")
public class FolderController {

    private final FolderService folderService;
    private final FileStorageService fileStorageService;
    private final ZipTicketService zipTicketService;
    private final AuditService auditService;

    @GetMapping("/tree")
    public Result<FolderTreeResponse> tree(
            @RequestParam(value = "dir", required = false, defaultValue = "") String dir,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "sort", required = false, defaultValue = "time_desc") String sort,
            @RequestParam(value = "scope", required = false) String scope,
            @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(folderService.tree(dir, keyword, category, sort, user, scope));
    }

    @PostMapping("/folder")
    public Result<FolderItem> createFolder(@Valid @RequestBody CreateFolderRequest req,
                                           @AuthenticationPrincipal UserPrincipal user,
                                           HttpServletRequest request) {
        FolderItem item = folderService.createFolder(req.path(), user);
        auditService.record(user, "FOLDER_CREATE", "FOLDER", item.path(), null, request.getRemoteAddr());
        return Result.ok(item);
    }

    @PostMapping("/{fileId}/move")
    public Result<FileVO> moveFile(@PathVariable("fileId") String fileId,
                                   @Valid @RequestBody MoveFileRequest req,
                                   @AuthenticationPrincipal UserPrincipal user,
                                   HttpServletRequest request) {
        FileVO vo = folderService.moveFile(fileId, req.targetDir(), req.newName(), user);
        auditService.record(user, "FILE_MOVE", "FILE", fileId,
                vo.relativePath().isEmpty() ? vo.fileName() : vo.relativePath(), request.getRemoteAddr());
        return Result.ok(vo);
    }

    @PostMapping("/folder/move")
    public Result<Map<String, Integer>> moveFolder(@Valid @RequestBody MoveFolderRequest req,
                                                   @AuthenticationPrincipal UserPrincipal user,
                                                   HttpServletRequest request) {
        int updated = folderService.renameOrMoveFolder(req.sourcePath(), req.targetPath(), user);
        auditService.record(user, "FOLDER_MOVE", "FOLDER", req.sourcePath(),
                "-> " + req.targetPath(), request.getRemoteAddr());
        return Result.ok(Map.of("updated", updated));
    }

    @PostMapping("/folder/trash")
    public Result<Map<String, Integer>> trashFolder(@RequestBody Map<String, String> body,
                                                    @AuthenticationPrincipal UserPrincipal user,
                                                    HttpServletRequest request) {
        String folderPath = body == null ? null : body.get("folderPath");
        if (folderPath == null || folderPath.isBlank()) {
            throw new BizException(10001, "folderPath 不能为空");
        }
        int trashed = folderService.trashFolder(folderPath, user);
        auditService.record(user, "FOLDER_TRASH", "FOLDER", folderPath,
                "files=" + trashed, request.getRemoteAddr());
        return Result.ok(Map.of("trashed", trashed));
    }

    @PostMapping("/batch/move")
    public Result<BatchOpResponse> batchMove(@Valid @RequestBody BatchMoveRequest req,
                                             @AuthenticationPrincipal UserPrincipal user,
                                             HttpServletRequest request) {
        BatchOpResponse resp = folderService.batchMove(req.fileIds(), req.targetDir(), user);
        auditService.record(user, "BATCH_MOVE", "FILE", null,
                "ok=" + resp.succeeded() + " dir=" + req.targetDir(), request.getRemoteAddr());
        return Result.ok(resp);
    }

    @PostMapping("/batch/trash")
    public Result<BatchOpResponse> batchTrash(@Valid @RequestBody BatchFileIdsRequest req,
                                              @AuthenticationPrincipal UserPrincipal user,
                                              HttpServletRequest request) {
        int ok = 0;
        int fail = 0;
        for (String id : new LinkedHashSet<>(req.fileIds())) {
            try {
                fileStorageService.moveToTrash(id, user);
                ok++;
            } catch (RuntimeException e) {
                fail++;
            }
        }
        auditService.record(user, "BATCH_TRASH", "FILE", null, "ok=" + ok, request.getRemoteAddr());
        return Result.ok(new BatchOpResponse(ok, fail));
    }

    @PostMapping("/batch/restore")
    public Result<BatchOpResponse> batchRestore(@Valid @RequestBody BatchFileIdsRequest req,
                                                @AuthenticationPrincipal UserPrincipal user,
                                                HttpServletRequest request) {
        int ok = 0;
        int fail = 0;
        for (String id : new LinkedHashSet<>(req.fileIds())) {
            try {
                fileStorageService.restoreFromTrash(id, user);
                ok++;
            } catch (RuntimeException e) {
                fail++;
            }
        }
        auditService.record(user, "BATCH_RESTORE", "FILE", null, "ok=" + ok, request.getRemoteAddr());
        return Result.ok(new BatchOpResponse(ok, fail));
    }

    @PostMapping("/batch/delete")
    public Result<BatchOpResponse> batchDelete(@Valid @RequestBody BatchFileIdsRequest req,
                                               @AuthenticationPrincipal UserPrincipal user,
                                               HttpServletRequest request) {
        int ok = 0;
        int fail = 0;
        for (String id : new LinkedHashSet<>(req.fileIds())) {
            try {
                fileStorageService.delete(id, user);
                ok++;
            } catch (RuntimeException e) {
                fail++;
            }
        }
        auditService.record(user, "BATCH_DELETE", "FILE", null, "ok=" + ok, request.getRemoteAddr());
        return Result.ok(new BatchOpResponse(ok, fail));
    }

    @PostMapping("/zip-ticket")
    public Result<ZipTicketResponse> zipTicket(@Valid @RequestBody ZipTicketRequest req,
                                               @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(folderService.issueZipTicket(req, user));
    }

    /**
     * 流式打包下载：边从 StorageBackend 读边压缩写入 ZipOutputStream，不落大临时文件、内存恒定。
     */
    @GetMapping("/zip")
    public ResponseEntity<StreamingResponseBody> downloadZip(
            @RequestParam("ticket") String ticket,
            HttpServletRequest request) {
        ZipTicketService.ResolvedZipTicket resolved = zipTicketService.resolve(ticket);
        if (resolved == null) {
            throw AuthException.unauthorized("打包下载票据无效或已过期");
        }
        auditService.record(resolved.user(), "ZIP_DOWNLOAD", "FILE", null,
                resolved.zipName() + " (" + resolved.items().size() + ")", request.getRemoteAddr());

        String encoded = URLEncoder.encode(resolved.zipName(), StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);

        StreamingResponseBody body = out -> {
            byte[] buffer = new byte[64 * 1024];
            Set<String> usedEntries = new HashSet<>();
            try (ZipOutputStream zos = new ZipOutputStream(out)) {
                zos.setLevel(Deflater.BEST_SPEED);
                for (ZipTicketService.ZipItem item : resolved.items()) {
                    FileInfo info = fileStorageService.requireReadableInfo(item.fileId(), resolved.user());
                    String entryPath = uniqueEntryPath(item.entryPath(), usedEntries);
                    ZipEntry entry = new ZipEntry(entryPath);
                    entry.setSize(info.getFileSize());
                    zos.putNextEntry(entry);
                    try (InputStream in = fileStorageService.openForRead(info)) {
                        int n;
                        while ((n = in.read(buffer)) >= 0) {
                            if (n > 0) {
                                zos.write(buffer, 0, n);
                            }
                        }
                    }
                    zos.closeEntry();
                }
                zos.finish();
            }
        };

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    private static String uniqueEntryPath(String rawPath, Set<String> used) {
        String normalized = rawPath == null ? "" : rawPath.replace('\\', '/');
        StringBuilder safeBuilder = new StringBuilder();
        for (String seg : normalized.split("/")) {
            StringBuilder cleaned = new StringBuilder();
            seg.codePoints()
                    .filter(cp -> !Character.isISOControl(cp))
                    .forEach(cleaned::appendCodePoint);
            String s = cleaned.toString().trim();
            if (s.isEmpty() || ".".equals(s) || "..".equals(s) || s.matches("^[a-zA-Z]:.*")) {
                continue;
            }
            if (!safeBuilder.isEmpty()) {
                safeBuilder.append('/');
            }
            safeBuilder.append(s);
        }
        String clean = safeBuilder.isEmpty() ? "file" : safeBuilder.toString();
        if (used.add(clean)) {
            return clean;
        }
        int slash = clean.lastIndexOf('/');
        String dir = slash >= 0 ? clean.substring(0, slash + 1) : "";
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            String candidate = dir + base + " (" + i + ")" + ext;
            if (used.add(candidate)) {
                return candidate;
            }
        }
        return dir + base + "-" + System.nanoTime() + ext;
    }
}
