package com.yuzhuo.filehub;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuo.filehub.auth.AuthService;
import com.yuzhuo.filehub.auth.DownloadTicketService;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.dto.AuthResponse;
import com.yuzhuo.filehub.dto.CreateShareRequest;
import com.yuzhuo.filehub.dto.InitUploadRequest;
import com.yuzhuo.filehub.dto.InitUploadResponse;
import com.yuzhuo.filehub.dto.LoginRequest;
import com.yuzhuo.filehub.dto.PageResponse;
import com.yuzhuo.filehub.dto.RegisterRequest;
import com.yuzhuo.filehub.dto.ShareCreatedResponse;
import com.yuzhuo.filehub.dto.ShareMetaResponse;
import com.yuzhuo.filehub.dto.ShareVerifyResponse;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.service.FileStorageService;
import com.yuzhuo.filehub.service.ShareService;
import com.yuzhuo.filehub.util.HashUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 桌面版真实数据库冒烟：不起 MySQL，用 H2 文件库把「注册 → 分片 → 合并 →
 * 搜索 → 下载 → 清理」整条链路跑一遍。
 *
 * <p>重点覆盖 MySQL 方言修补：DATE_SUB 改成传入 cutoff、分片 upsert、
 * 分页方言自动识别、全文搜索回退 LIKE、用户名大小写不敏感。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.port=0",
                "file-hub.cleanup-initial-delay-ms=3600000",
                "file-hub.rate-limit.enabled=false"
        })
@ActiveProfiles("desktop")
@AutoConfigureMockMvc
class DesktopDatabaseIntegrationTest {

    private static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("filehub-desktop-test-");
            System.setProperty("filehub.data.dir",
                    DATA_DIR.toString().replace('\\', '/'));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    AuthService authService;

    @Autowired
    FileStorageService service;

    @Autowired
    FileInfoMapper fileInfoMapper;

    @Autowired
    ShareService shareService;

    @Autowired
    DownloadTicketService ticketService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("桌面 H2：注册/大小写登录/分片重传/合并/搜索/下载/清理")
    void fullDesktopFlow() throws Exception {
        // 1. 第一个注册用户是管理员；用户名大小写不敏感（MySQL 默认 collation 的语义）
        AuthResponse registered = authService.register(new RegisterRequest("Alice", "filehub-test-pass"));
        assertEquals("ADMIN", registered.user().role());
        assertTrue(Files.exists(DATA_DIR.resolve("db/filehub.mv.db")), "H2 数据文件应已创建");

        AuthResponse loggedIn = authService.login(new LoginRequest("alice", "filehub-test-pass"));
        UserPrincipal user = new UserPrincipal(
                loggedIn.user().id(), loggedIn.user().username(), loggedIn.user().role());

        // 2. 上传：分片重复提交两次，验证 H2 的 ON DUPLICATE KEY UPDATE
        byte[] content = "file-hub desktop 桌面版 测试内容".getBytes(StandardCharsets.UTF_8);
        String fileHash = HashUtil.sha256Hex(content);
        InitUploadResponse init = service.init(
                new InitUploadRequest("桌面测试.txt", (long) content.length, fileHash), user);
        String fileId = init.fileId();
        String chunkHash = HashUtil.sha256Hex(content);

        service.saveChunk(fileId, 0, new ByteArrayInputStream(content), chunkHash, user);
        service.saveChunk(fileId, 0, new ByteArrayInputStream(content), chunkHash, user);
        service.complete(fileId, user);

        FileInfo done = service.requireInfo(fileId, user);
        assertEquals(FileInfo.STATUS_DONE, done.getStatus());
        assertEquals(fileHash, done.getFileHash());

        // 3. 关键词搜索走 LIKE 回退（desktop profile 关闭了 fulltext）
        PageResponse<?> page = service.list(1, 10, "桌面", null, "time_desc", user, null);
        assertEquals(1, page.total());

        // 4. 下载内容与原始字节一致
        try (InputStream in = service.openForRead(done)) {
            assertTrue(java.util.Arrays.equals(content, in.readAllBytes()));
        }

        // 5. 清理过期上传任务：DATE_SUB 改成 Java cutoff 后仍需在 H2 上工作
        InitUploadResponse stale = service.init(
                new InitUploadRequest("stale.bin", 12L, "a".repeat(64)), user);
        jdbcTemplate.update("UPDATE file_info SET update_time = ? WHERE file_id = ?",
                LocalDateTime.now().minusDays(2), stale.fileId());
        service.cleanupExpiredUploads();

        FileInfo removed = fileInfoMapper.selectOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getFileId, stale.fileId()));
        assertNull(removed, "过期上传任务应被删除");

        assertNotNull(done.getStoragePath());
    }

    @Test
    @DisplayName("桌面 H2：分享创建/校验/票据下载/锁定/重置/取消")
    void shareFlow() throws Exception {
        AuthResponse registered = authService.register(
                new RegisterRequest("ShareUser", "filehub-share-pass"));
        UserPrincipal user = new UserPrincipal(
                registered.user().id(), registered.user().username(), registered.user().role());

        byte[] content = "share 分享测试内容".getBytes(StandardCharsets.UTF_8);
        String fileHash = HashUtil.sha256Hex(content);
        InitUploadResponse init = service.init(
                new InitUploadRequest("分享测试.bin", (long) content.length, fileHash), user);
        service.saveChunk(init.fileId(), 0, new ByteArrayInputStream(content),
                HashUtil.sha256Hex(content), user);
        service.complete(init.fileId(), user);

        ShareCreatedResponse created = shareService.create(
                new CreateShareRequest(init.fileId(), 7), user);
        assertEquals(4, created.code().length());
        assertNotNull(created.expiresAt());

        ShareMetaResponse meta = shareService.meta(created.token());
        assertEquals(ShareService.STATE_ACTIVE, meta.state());

        String wrong = "AAAA".equals(created.code()) ? "BBBB" : "AAAA";
        BizException wrongEx = assertThrows(BizException.class,
                () -> shareService.verify(created.token(), wrong));
        assertEquals(42003, wrongEx.getCode());

        // 提取码大小写归一后应通过；票据兑换回创建者身份
        ShareVerifyResponse ok = shareService.verify(created.token(), created.code().toLowerCase());
        assertEquals("分享测试.bin", ok.fileName());
        assertTrue(ok.downloadUrl().contains(
                "/api/file/" + init.fileId() + "/download?ticket="));
        String ticket = ok.downloadUrl().substring(
                ok.downloadUrl().indexOf("ticket=") + "ticket=".length());
        assertNotNull(ticketService.resolve(init.fileId(), ticket));
        try (InputStream in = service.openForRead(service.requireInfo(init.fileId(), user))) {
            assertArrayEquals(content, in.readAllBytes());
        }

        shareService.cancel(created.token(), user);
        assertEquals(42001, assertThrows(BizException.class,
                () -> shareService.meta(created.token())).getCode());

        // 永久分享：连续错 10 次锁定，重置提取码后立即恢复
        ShareCreatedResponse permanent = shareService.create(
                new CreateShareRequest(init.fileId(), 0), user);
        assertNull(permanent.expiresAt());
        String wrong2 = "AAAA".equals(permanent.code()) ? "BBBB" : "AAAA";
        for (int i = 0; i < 9; i++) {
            BizException ex = assertThrows(BizException.class,
                    () -> shareService.verify(permanent.token(), wrong2));
            assertEquals(42003, ex.getCode());
        }
        assertEquals(42004, assertThrows(BizException.class,
                () -> shareService.verify(permanent.token(), wrong2)).getCode());
        assertEquals(42004, assertThrows(BizException.class,
                () -> shareService.verify(permanent.token(), permanent.code())).getCode());

        var reset = shareService.resetCode(permanent.token(), user);
        assertEquals(4, reset.code().length());
        ShareVerifyResponse afterReset = shareService.verify(permanent.token(), reset.code());
        assertEquals(init.fileId(), afterReset.fileId());
    }

    @Test
    @DisplayName("匿名分享接口放行，管理接口仍需登录")
    void shareSecurity() throws Exception {
        String token = "AAAABBBBCCCCDDDDEEEEFFFF";

        mockMvc.perform(get("/api/public/share/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(42001));

        mockMvc.perform(post("/api/public/share/" + token + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABCD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(42001));

        mockMvc.perform(get("/api/share/mine"))
                .andExpect(status().isUnauthorized());
    }

    @AfterAll
    static void cleanup() {
        try (var paths = Files.walk(DATA_DIR)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Windows 上 H2 已关闭仍可能短暂占用文件，留给临时目录清理
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
