package com.yuzhuo.filehub.controller;

import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.dto.CheckResponse;
import com.yuzhuo.filehub.dto.FileVO;
import com.yuzhuo.filehub.dto.InitUploadRequest;
import com.yuzhuo.filehub.dto.InitUploadResponse;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.service.FileStorageService;
import com.yuzhuo.filehub.storage.StorageBackend;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** 上传一个分片 */
    @PostMapping("/chunk")
    public Result<Void> uploadChunk(@RequestParam("fileId") String fileId,
                                    @RequestParam("index") int index,
                                    @RequestPart("chunk") MultipartFile chunk) throws IOException {
        try (InputStream in = chunk.getInputStream()) {
            service.saveChunk(fileId, index, in);
        }
        return Result.ok();
    }

    /** 合并分片 */
    @PostMapping("/{fileId}/complete")
    public Result<FileVO> complete(@PathVariable("fileId") String fileId) {
        return Result.ok(service.complete(fileId));
    }

    @GetMapping("/list")
    public Result<List<FileVO>> list() {
        return Result.ok(service.list());
    }

    /** 下载：边读边写，不把整个文件读进内存 */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable("fileId") String fileId) {
        FileInfo info = service.requireInfo(fileId);
        InputStream in = service.openForRead(info);

        // 文件名可能含中文/空格，必须走 RFC 5987 的 filename* 形式，否则浏览器的响应头会乱码
        String encoded = URLEncoder.encode(info.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(info.getFileSize())
                .body(new InputStreamResource(in));
    }

    @DeleteMapping("/{fileId}")
    public Result<Void> delete(@PathVariable("fileId") String fileId) {
        service.delete(fileId);
        return Result.ok();
    }
}
