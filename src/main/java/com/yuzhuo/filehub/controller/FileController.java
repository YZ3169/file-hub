package com.yuzhuo.filehub.controller;

import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.dto.CheckResponse;
import com.yuzhuo.filehub.dto.FileVO;
import com.yuzhuo.filehub.dto.InitUploadRequest;
import com.yuzhuo.filehub.dto.InitUploadResponse;
import com.yuzhuo.filehub.dto.PageResponse;
import com.yuzhuo.filehub.dto.StatsResponse;
import com.yuzhuo.filehub.dto.UploadStatusResponse;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.service.FileStorageService;
import com.yuzhuo.filehub.storage.StorageBackend;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService service;
    private final StorageBackend storageBackend;
    private final StorageProperties props;

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
                                       @RequestParam("size") long size) {
        return Result.ok(service.check(hash, size));
    }

    /** 初始化上传，拿 fileId + 需要补传的分片序号 */
    @PostMapping("/init")
    public Result<InitUploadResponse> init(@RequestBody @Valid InitUploadRequest req) {
        return Result.ok(service.init(req));
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
                                    @RequestPart("chunk") MultipartFile chunk) throws IOException {
        try (InputStream in = chunk.getInputStream()) {
            service.saveChunk(fileId, index, in, chunkHash);
        }
        return Result.ok();
    }

    /** 合并分片 */
    @PostMapping("/{fileId}/complete")
    public Result<FileVO> complete(@PathVariable("fileId") String fileId) {
        return Result.ok(service.complete(fileId));
    }

    /** 上传任务状态：以磁盘为准的已传分片，断点续传/坏片补传前先对齐一次 */
    @GetMapping("/{fileId}/status")
    public Result<UploadStatusResponse> status(@PathVariable("fileId") String fileId) {
        return Result.ok(service.status(fileId));
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
            @RequestParam(value = "sort", defaultValue = "time_desc") String sort) {
        return Result.ok(service.list(page, size, keyword, status, sort));
    }

    @GetMapping("/stats")
    public Result<StatsResponse> stats() {
        return Result.ok(service.stats());
    }

    /**
     * 下载。支持 HTTP Range，浏览器/下载器断点续传下载时只请求缺的那一段；
     * 流式输出，不把整个文件读进内存。
     */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable("fileId") String fileId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        FileInfo info = service.requireInfo(fileId);
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
        // 多段 Range 要 multipart/byteranges，实现成本高于收益；按 RFC 允许直接返回整文件
        if (ranges.size() != 1) {
            return fullResponse(info, headers);
        }

        long start;
        long end;
        try {
            start = ranges.get(0).getRangeStart(size);
            end = ranges.get(0).getRangeEnd(size);
        } catch (IllegalArgumentException e) {
            return rangeNotSatisfiable(size);
        }
        if (start >= size) {
            return rangeNotSatisfiable(size);
        }

        long length = end - start + 1;
        InputStream in = service.openForRead(info);
        try {
            skipFully(in, start);
        } catch (IOException e) {
            closeQuietly(in);
            throw new com.yuzhuo.filehub.common.BizException(20006, "读取文件失败：" + e.getMessage());
        }

        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(length)
                .body(new InputStreamResource(new BoundedInputStream(in, length)));
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

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new IOException("文件实际长度小于索引记录");
                }
                skipped = 1;
            }
            n -= skipped;
        }
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
    public Result<Void> delete(@PathVariable("fileId") String fileId) {
        service.delete(fileId);
        return Result.ok();
    }
}
