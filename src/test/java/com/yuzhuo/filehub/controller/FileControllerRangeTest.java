package com.yuzhuo.filehub.controller;

import com.yuzhuo.filehub.auth.DownloadTicketService;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.service.FileStorageService;
import com.yuzhuo.filehub.storage.StorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileControllerRangeTest {

    static final String ID = "1f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";
    static final UserPrincipal USER = new UserPrincipal(7L, "alice", "USER");
    static final String CONTENT = "0123456789";
    static final Pattern BOUNDARY = Pattern.compile("boundary=(\\S+)");

    @Mock
    FileStorageService service;
    @Mock
    StorageBackend storageBackend;
    @Mock
    DownloadTicketService ticketService;

    FileController controller;
    FileInfo info;

    @BeforeEach
    void setUp() {
        controller = new FileController(service, storageBackend, new StorageProperties(), ticketService);
        info = new FileInfo();
        info.setFileId(ID);
        info.setFileName("测试.bin");
        info.setFileHash("ab".repeat(32));
        info.setFileSize((long) CONTENT.length());
        info.setStatus(FileInfo.STATUS_DONE);
        when(service.requireInfo(ID, USER)).thenReturn(info);
    }

    @Test
    @DisplayName("单段 Range 仍是 206 + Content-Range，并且只打开该区间")
    void singleRangeUsesOffsetRead() throws Exception {
        when(service.openRangeForRead(eq(info), eq(4L), eq(3L)))
                .thenReturn(new ByteArrayInputStream(CONTENT.substring(4, 7).getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<InputStreamResource> resp = controller.download(ID, null, "bytes=4-6", USER);

        assertEquals(HttpStatus.PARTIAL_CONTENT, resp.getStatusCode());
        assertEquals("bytes 4-6/10", resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(3, resp.getHeaders().getContentLength());
        assertArrayEquals("456".getBytes(StandardCharsets.UTF_8), body(resp));
    }

    @Test
    @DisplayName("多段 Range 返回 multipart/byteranges，边界、段头、Content-Length 都正确")
    void multiRangeProducesMultipartBody() throws Exception {
        stubOpenRange();

        ResponseEntity<InputStreamResource> resp = controller.download(ID, null, "bytes=0-2,5-7", USER);

        assertEquals(HttpStatus.PARTIAL_CONTENT, resp.getStatusCode());
        String contentType = resp.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        assertNotNull(contentType);
        assertTrue(contentType.startsWith("multipart/byteranges; boundary="), contentType);
        Matcher m = BOUNDARY.matcher(contentType);
        assertTrue(m.find(), contentType);
        String boundary = m.group(1);

        byte[] raw = body(resp);
        assertEquals(raw.length, resp.getHeaders().getContentLength(),
                "Content-Length 必须和实际字节数完全一致");

        String expected = "--" + boundary + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Range: bytes 0-2/10\r\n\r\n"
                + "012\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Range: bytes 5-7/10\r\n\r\n"
                + "567\r\n"
                + "--" + boundary + "--\r\n";
        assertEquals(expected, new String(raw, StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("段数超过 8 直接 416，不打开任何流")
    void tooManyRangesIs416() {
        String header = "bytes=0-0,1-1,2-2,3-3,4-4,5-5,6-6,7-7,8-8";
        ResponseEntity<InputStreamResource> resp = controller.download(ID, null, header, USER);

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, resp.getStatusCode());
        assertEquals("bytes */10", resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    @DisplayName("多段总长度超过 64MB 直接 416")
    void oversizedTotalIs416() {
        info.setFileSize(200L * 1024 * 1024);
        // 5 段各 16MB，合计 80MB > 64MB 上限
        String header = "bytes=0-16777215,16777216-33554431,33554432-50331647,"
                + "50331648-67108863,67108864-83886079";
        ResponseEntity<InputStreamResource> resp = controller.download(ID, null, header, USER);

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, resp.getStatusCode());
    }

    @Test
    @DisplayName("语法非法或区间越界的 Range 返回 416")
    void invalidRangesAre416() {
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                controller.download(ID, null, "bytes=abc", USER).getStatusCode());
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                controller.download(ID, null, "bytes=10-20", USER).getStatusCode());
    }

    @Test
    @DisplayName("没有 Range 时返回整文件 200")
    void noRangeReturnsFullFile() throws Exception {
        when(service.openForRead(info))
                .thenReturn(new ByteArrayInputStream(CONTENT.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<InputStreamResource> resp = controller.download(ID, null, null, USER);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(10, resp.getHeaders().getContentLength());
        assertArrayEquals(CONTENT.getBytes(StandardCharsets.UTF_8), body(resp));
    }

    private void stubOpenRange() {
        when(service.openRangeForRead(eq(info), anyLong(), anyLong())).thenAnswer(inv -> {
            long offset = inv.getArgument(1);
            long length = inv.getArgument(2);
            return new ByteArrayInputStream(
                    CONTENT.substring((int) offset, (int) (offset + length)).getBytes(StandardCharsets.UTF_8));
        });
    }

    private static byte[] body(ResponseEntity<InputStreamResource> resp) throws IOException {
        assertNotNull(resp.getBody());
        try (var in = resp.getBody().getInputStream()) {
            return in.readAllBytes();
        }
    }
}
