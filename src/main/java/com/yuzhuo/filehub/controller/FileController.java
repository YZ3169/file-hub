package com.yuzhuo.filehub.controller;

import com.yuzhuo.filehub.auth.AuthException;
import com.yuzhuo.filehub.auth.DownloadTicketService;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.dto.CheckResponse;
import com.yuzhuo.filehub.dto.DownloadTicketResponse;
import com.yuzhuo.filehub.dto.FileVO;
import com.yuzhuo.filehub.dto.InitUploadRequest;
import com.yuzhuo.filehub.dto.InitUploadResponse;
import com.yuzhuo.filehub.dto.PageResponse;
import com.yuzhuo.filehub.dto.StatsResponse;
import com.yuzhuo.filehub.dto.UploadStatusResponse;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.service.FileStorageService;
import com.yuzhuo.filehub.storage.StorageBackend;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@Tag(name = "文件", description = "初始化 / 分片 / 合并 / 列表 / 下载 / 删除")
public class FileController {

    /** 多段 Range 的上限：段数与总字节数，超过一律 416 */
    private static final int MAX_RANGES = 8;
    private static final long MAX_MULTIPART_BYTES = 64L * 1024 * 1024;
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private final FileStorageService service;
    private final StorageBackend storageBackend;
    private final StorageProperties props;
    private final DownloadTicketService ticketService;

    /** 前端启动时读一次，拿到分片大小和存储后端标识 */
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        return Result.ok(Map.of(
                "chunkSize", props.getChunkSize(),
                "backend", storageBackend.backendName()));
    }

    /** 秒传预检 */
    @GetMapping("/check")
    public Result<CheckResponse> check(@RequestParam("hash") String hash,
                                       @RequestParam("size") long size,
                                       @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(service.check(hash, size, user));
    }

    /** 初始化上传，拿 fileId + 需要补传的分片序号 */
    @PostMapping("/init")
    public Result<InitUploadResponse> init(@RequestBody @Valid InitUploadRequest req,
                                           @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(service.init(req, user));
    }

    /**
     * 上传一个分片。
     *
     * @param chunkHash 可选，分片内容的 SHA-256。带上它，服务端就能在落盘时拦下
     *                  传输损坏的那一片，让客户端只重传这一片，而不是整文件重来。
     */
    @PostMapping("/chunk")
    public Result<Void> uploadChunk(@RequestParam("fileId") String fileId,
                                    @RequestParam("index") int index,
                                    @RequestParam(value = "chunkHash", required = false) String chunkHash,
                                    @RequestPart("chunk") MultipartFile chunk,
                                    @AuthenticationPrincipal UserPrincipal user) throws IOException {
        try (InputStream in = chunk.getInputStream()) {
            service.saveChunk(fileId, index, in, chunkHash, user);
        }
        return Result.ok();
    }

    /** 合并分片 */
    @PostMapping("/{fileId}/complete")
    public Result<FileVO> complete(@PathVariable("fileId") String fileId,
                                   @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(service.complete(fileId, user));
    }

    /** 上传任务状态：以磁盘为准的已传分片，断点续传/坏片补传前先对齐一次 */
    @GetMapping("/{fileId}/status")
    public Result<UploadStatusResponse> status(@PathVariable("fileId") String fileId,
                                               @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(service.status(fileId, user));
    }

    /**
     * 分页文件列表。
     *
     * @param page    页码，从 1 开始（服务端会夹到 ≥ 1）
     * @param size    每页条数，1–100（服务端会夹紧）
     * @param keyword 可选，按文件名 / SHA-256 / fileId 模糊匹配
     * @param status  可选，0=上传中 1=已完成
     * @param sort    time_desc（默认）/ time_asc / size_desc / size_asc / name_asc
     */
    @GetMapping("/list")
    public Result<PageResponse<FileVO>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "sort", defaultValue = "time_desc") String sort,
            @RequestParam(value = "scope", required = false) String scope,
            @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(service.list(page, size, keyword, status, sort, user, scope));
    }

    @GetMapping("/stats")
    public Result<StatsResponse> stats(
            @RequestParam(value = "scope", required = false) String scope,
            @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(service.stats(user, scope));
    }

    /**
     * 浏览器下载票据：Bearer Token 不能放进 &lt;a href&gt;，先换一张 5 分钟的短期票据。
     * 票据必须由 owner 或管理员申请，绑定 fileId + userId。
     */
    @GetMapping("/{fileId}/download-ticket")
    public Result<DownloadTicketResponse> downloadTicket(
            @PathVariable("fileId") String fileId,
            @AuthenticationPrincipal UserPrincipal user) {
        service.requireInfo(fileId, user);
        return Result.ok(ticketService.issue(fileId, user));
    }

    /**
     * 下载。支持 HTTP Range，浏览器/下载器断点续传下载时只请求缺的那一段；
     * 流式输出，不把整个文件读进内存。
     */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable("fileId") String fileId,
            @RequestParam(value = "ticket", required = false) String ticket,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @AuthenticationPrincipal UserPrincipal user) {
        // 下载接口在 SecurityConfig 里放行，鉴权必须在这里完成：
        // 优先兑换票据（浏览器），否则使用 Bearer Token（脚本/curl）。两者都没有就是 401。
        UserPrincipal accessor = user;
        if (ticket != null && !ticket.isBlank()) {
            accessor = ticketService.resolve(fileId, ticket);
            if (accessor == null) {
                throw AuthException.unauthorized("下载票据无效或已过期");
            }
        }
        if (accessor == null) {
            throw AuthException.unauthorized("请先登录或使用下载票据");
        }
        FileInfo info = service.requireInfo(fileId, accessor);
        long size = info.getFileSize();
        HttpHeaders headers = downloadHeaders(info);

        if (rangeHeader == null || rangeHeader.isBlank()) {
            return fullResponse(info, headers);
        }

        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            return rangeNotSatisfiable(size);
        }
        if (ranges.isEmpty()) {
            return fullResponse(info, headers);
        }
        // 多段请求最多 8 段、总计 64MB：再大就不值得为「并行下载」多开这么多流，
        // 也避免一个请求被构造出几十万个区间把内存/连接占满。
        if (ranges.size() > MAX_RANGES) {
            return rangeNotSatisfiable(size);
        }

        long[][] spans = new long[ranges.size()][];
        long total = 0;
        for (int i = 0; i < ranges.size(); i++) {
            long start;
            long end;
            try {
                start = ranges.get(i).getRangeStart(size);
                end = ranges.get(i).getRangeEnd(size);
            } catch (IllegalArgumentException e) {
                return rangeNotSatisfiable(size);
            }
            if (start < 0 || start >= size || end < start || end >= size) {
                return rangeNotSatisfiable(size);
            }
            total += end - start + 1;
            if (total > MAX_MULTIPART_BYTES) {
                return rangeNotSatisfiable(size);
            }
            spans[i] = new long[]{start, end};
        }

        if (spans.length == 1) {
            long start = spans[0][0];
            long end = spans[0][1];
            long length = end - start + 1;
            InputStream in = service.openRangeForRead(info, start, length);
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(length)
                    .body(new InputStreamResource(new BoundedInputStream(in, length)));
        }
        return multipartResponse(info, headers, size, spans);
    }

    /**
     * RFC 7233 的多段响应：body 由若干「分界行 + 段头 + 本段字节 + CRLF」组成，
     * 最后以 {@code --boundary--} 收尾。所有段头都是纯 ASCII，因此 Content-Length
     * 可以在发送前精确算出来——这对不支持 chunked 的老下载器很重要。
     */
    private ResponseEntity<InputStreamResource> multipartResponse(FileInfo info, HttpHeaders headers,
                                                                  long size, long[][] spans) {
        String boundary = "filehub-" + UUID.randomUUID().toString().replace("-", "");
        long contentLength = 0;
        List<InputStream> parts = new ArrayList<>(spans.length * 3 + 1);
        try {
            for (long[] span : spans) {
                long start = span[0];
                long end = span[1];
                long length = end - start + 1;
                byte[] partHeader = ("--" + boundary + "\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
                parts.add(new ByteArrayInputStream(partHeader));
                // 每段单独打开、只暴露本段：FileChannel 直接定位，不会从文件头开始空读
                parts.add(new BoundedInputStream(service.openRangeForRead(info, start, length), length));
                parts.add(new ByteArrayInputStream(CRLF));
                contentLength += partHeader.length + length + CRLF.length;
            }
            byte[] closing = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
            parts.add(new ByteArrayInputStream(closing));
            contentLength += closing.length;
        } catch (RuntimeException e) {
            // 中途某个区间打不开：已经打开的流必须关掉，否则句柄泄漏
            parts.forEach(FileController::closeQuietly);
            throw e;
        }

        headers.set(HttpHeaders.CONTENT_TYPE, "multipart/byteranges; boundary=" + boundary);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .headers(headers)
                .contentLength(contentLength)
                .body(new InputStreamResource(new SequenceInputStream(Collections.enumeration(parts))));
    }

    private ResponseEntity<InputStreamResource> fullResponse(FileInfo info, HttpHeaders headers) {
        InputStream in = service.openForRead(info);
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(info.getFileSize())
                .body(new InputStreamResource(in));
    }

    private HttpHeaders downloadHeaders(FileInfo info) {
        // 文件名可能含中文/空格，必须走 RFC 5987 的 filename* 形式，否则浏览器的响应头会乱码
        String encoded = URLEncoder.encode(info.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        // 支持断点续传下载；ETag 用内容哈希，内容相同即相同版本
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setETag("\"" + info.getFileHash() + "\"");
        return headers;
    }

    private static ResponseEntity<InputStreamResource> rangeNotSatisfiable(long size) {
        return ResponseEntity.<InputStreamResource>status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                .build();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭失败不影响已经要返回的错误
        }
    }

    /** 只向外暴露请求区间内的字节，读完自动 EOF，避免把整个文件发出去 */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = delegate.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = delegate.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(delegate.available(), remaining);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    @DeleteMapping("/{fileId}")
    public Result<Void> delete(@PathVariable("fileId") String fileId,
                               @AuthenticationPrincipal UserPrincipal user) {
        service.delete(fileId, user);
        return Result.ok();
    }
}
