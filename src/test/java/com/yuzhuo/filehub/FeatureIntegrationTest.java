package com.yuzhuo.filehub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuo.filehub.desktop.DesktopSyncService;
import com.yuzhuo.filehub.util.HashUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.port=0",
                "file-hub.cleanup-initial-delay-ms=3600000",
                "file-hub.rate-limit.enabled=false"
        })
@ActiveProfiles("desktop")
@AutoConfigureMockMvc
class FeatureIntegrationTest {

    private static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("filehub-feature-test-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("filehub.data.dir", () -> DATA_DIR.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DesktopSyncService desktopSyncService;

    @LocalServerPort
    int port;

    @AfterAll
    static void cleanDataDir() throws IOException {
        try (var s = Files.walk(DATA_DIR)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    @DisplayName("完整端到端新特性：预览 / 目录树 / Zip 打包 / 回收站 / 配额 / 分享转存与限次 / 改密审计 / WebDAV / 桌面同步")
    void endToEndFeatures(@TempDir Path syncDir) throws Exception {
        // 1. 注册管理员（桌面首用户）和普通用户 bob
        JsonNode adminAuth = register("admin_feat", "password-123");
        assertEquals("ADMIN", adminAuth.get("user").get("role").asText());
        String adminToken = adminAuth.get("token").asText();

        JsonNode bobAuth = register("bob_feat", "password-123");
        String bobToken = bobAuth.get("token").asText();
        long bobId = bobAuth.get("user").get("id").asLong();

        // 2. 上传 SVG / HTML / MP4 用于在线预览安全校验
        String svgId = uploadSmallFile(adminToken, "logo.svg", "", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        String htmlId = uploadSmallFile(adminToken, "page.html", "docs", "<script>alert(1)</script>");

        mvc.perform(get("/api/file/" + svgId + "/download")
                        .param("inline", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("inline")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("image/svg+xml")))
                .andExpect(header().string("Content-Security-Policy", "sandbox"));

        mvc.perform(get("/api/file/" + htmlId + "/download")
                        .param("inline", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/plain")));

        // 3. 目录树、新建目录、移动、重命名文件夹
        mvc.perform(post("/api/file/folder")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"projects/2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(post("/api/file/" + svgId + "/move")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetDir\":\"projects/2026\",\"newName\":\"brand.svg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relativePath").value("projects/2026/brand.svg"));

        mvc.perform(get("/api/file/tree")
                        .param("dir", "projects/2026")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].fileName").value("brand.svg"));

        mvc.perform(get("/api/file/list")
                        .param("category", "image")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.fileName=='brand.svg')]").exists())
                .andExpect(jsonPath("$.data.items[?(@.fileName=='page.html')]").doesNotExist());

        mvc.perform(get("/api/file/tree")
                        .param("dir", "projects/2026")
                        .param("category", "image")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].fileName").value("brand.svg"));

        mvc.perform(get("/api/file/tree")
                        .param("dir", "projects/2026")
                        .param("category", "audio")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()").value(0));

        mvc.perform(get("/api/file/list")
                        .param("category", "unknown")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10008));

        // 4. 流式 Zip 打包下载
        MvcResult zipTicketRes = mvc.perform(post("/api/file/zip-ticket")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"folderPath\":\"projects\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileCount").value(1))
                .andReturn();
        String zipUrl = objectMapper.readTree(zipTicketRes.getResponse().getContentAsString())
                .get("data").get("downloadUrl").asText();

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<byte[]> zipRes = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + zipUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, zipRes.statusCode());
        assertEquals("application/zip", zipRes.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""));
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipRes.body()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\"/>", entries.get("2026/brand.svg"));

        // 5. 回收站（移入回收站 -> 列表隐藏 -> 恢复 -> 清空）
        mvc.perform(post("/api/file/" + htmlId + "/trash")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/file/trash")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].fileId").value(htmlId));

        mvc.perform(post("/api/file/" + htmlId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1));

        // 6. 分享高级特性：免提取码公开链接 + 最大提取次数 1 + 转存到 bob 网盘
        MvcResult shareRes = mvc.perform(post("/api/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"" + htmlId + "\",\"expiresInDays\":7,\"requireCode\":false,\"maxDownloads\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requireCode").value(false))
                .andReturn();
        String shareToken = objectMapper.readTree(shareRes.getResponse().getContentAsString())
                .get("data").get("token").asText();

        MvcResult verifyRes = mvc.perform(post("/api/public/share/" + shareToken + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("page.html"))
                .andReturn();
        String downloadUrl = objectMapper.readTree(verifyRes.getResponse().getContentAsString())
                .get("data").get("downloadUrl").asText();
        String ticket = downloadUrl.substring(downloadUrl.indexOf("ticket=") + 7);

        // 第 2 次提取应触发 42009 次数已达上限
        mvc.perform(post("/api/public/share/" + shareToken + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(42009));

        // 凭已签发 ticket 转存到 bob 网盘
        mvc.perform(post("/api/share/" + shareToken + "/save")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":\"" + ticket + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.fileName").value("page.html"));

        // 凭证立即生效可下载，取消分享后同一票据立刻被吊销
        mvc.perform(get(downloadUrl))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/share/" + shareToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mvc.perform(get(downloadUrl))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40001));

        // 7. 用户存储配额控制与安全响应头/Swagger 鉴权校验
        mvc.perform(get("/api/file/config"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().exists("Content-Security-Policy"));

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/admin/users/" + bobId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quotaBytes\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storageQuotaBytes").value(30));

        String bigHash = HashUtil.sha256Hex("x".repeat(100).getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/api/file/init")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"over.txt\",\"fileSize\":100,\"fileHash\":\"" + bigHash + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(41001));

        // WebDAV PUT 流式写入过程中实时拦截超额流量（返回 507）
        String bobPrePassBasic = "Basic " + Base64.getEncoder()
                .encodeToString("bob_feat:password-123".getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> quotaDavRes = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dav/too-big.bin"))
                        .header(HttpHeaders.AUTHORIZATION, bobPrePassBasic)
                        .PUT(HttpRequest.BodyPublishers.ofString("Z".repeat(500)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(507, quotaDavRes.statusCode());

        // 跨分享票据隔离：用 htmlId 的普通下载票据（无 shareId）绝不能绕过另一条加密分享
        MvcResult codeShareRes = mvc.perform(post("/api/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"" + htmlId + "\",\"expiresInDays\":7,\"requireCode\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        String codeShareToken = objectMapper.readTree(codeShareRes.getResponse().getContentAsString())
                .get("data").get("token").asText();
        MvcResult directTkRes = mvc.perform(get("/api/file/" + htmlId + "/download-ticket")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String directTicket = objectMapper.readTree(directTkRes.getResponse().getContentAsString())
                .get("data").get("ticket").asText();
        mvc.perform(post("/api/share/" + codeShareToken + "/save")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":\"" + directTicket + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(42006));

        // 8. 修改密码：当前会话保留可用，同账号其他设备旧会话全部踢出
        MvcResult secondLogin = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob_feat\",\"password\":\"password-123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String bobOtherToken = objectMapper.readTree(secondLogin.getResponse().getContentAsString())
                .get("data").get("token").asText();

        mvc.perform(post("/api/auth/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password-123\",\"newPassword\":\"new-pass-456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bobOtherToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/audit/logs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].action").value("PASSWORD_CHANGE"));

        // 管理员禁用用户时撤销其全部会话（即使重新启用，旧凭证也不能复活）
        JsonNode tempUser = register("temp_disable", "password-123");
        long tempId = tempUser.get("user").get("id").asLong();
        String tempToken = tempUser.get("token").asText();
        mvc.perform(put("/api/admin/users/" + tempId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tempToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/users/" + tempId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tempToken))
                .andExpect(status().isUnauthorized());

        // 9. WebDAV 协议真实 HTTP 端口测试（Basic Auth + SVG XSS 隔离头 + MKCOL + PUT + PROPFIND + GET Range + MOVE + DELETE）
        String bobBasic = "Basic " + Base64.getEncoder()
                .encodeToString("bob_feat:new-pass-456".getBytes(StandardCharsets.UTF_8));
        String davBase = "http://127.0.0.1:" + port + "/dav";

        HttpResponse<String> optionsRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, optionsRes.statusCode());
        String davCap = optionsRes.headers().firstValue("DAV").orElse("");
        assertTrue(davCap.contains("1"));
        assertFalse(davCap.contains("2"));

        HttpResponse<String> lockRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/webdav-dir"))
                        .header(HttpHeaders.AUTHORIZATION, bobBasic)
                        .method("LOCK", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, lockRes.statusCode());

        HttpResponse<String> unauthRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/"))
                        .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthRes.statusCode());
        assertTrue(unauthRes.headers().firstValue("WWW-Authenticate").orElse("").contains("Basic"));

        HttpResponse<String> mkcolRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/webdav-dir"))
                        .header(HttpHeaders.AUTHORIZATION, bobBasic)
                        .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, mkcolRes.statusCode());

        // 给 bob 放大配额再 PUT
        mvc.perform(put("/api/admin/users/" + bobId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quotaBytes\":-1}"))
                .andExpect(status().isOk());

        HttpResponse<String> putRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/webdav-dir/hello.txt"))
                        .header(HttpHeaders.AUTHORIZATION, bobBasic)
                        .PUT(HttpRequest.BodyPublishers.ofString("0123456789"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, putRes.statusCode());

        HttpResponse<String> propfindRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/webdav-dir"))
                        .header(HttpHeaders.AUTHORIZATION, bobBasic)
                        .header("Depth", "1")
                        .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(207, propfindRes.statusCode());
        assertTrue(propfindRes.body().contains("hello.txt"));

        HttpResponse<String> rangeRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/webdav-dir/hello.txt"))
                        .header(HttpHeaders.AUTHORIZATION, bobBasic)
                        .header(HttpHeaders.RANGE, "bytes=2-5")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(206, rangeRes.statusCode());
        assertEquals("2345", rangeRes.body());
        assertEquals("bytes 2-5/10", rangeRes.headers().firstValue(HttpHeaders.CONTENT_RANGE).orElse(""));

        HttpResponse<String> svgPutRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/webdav-dir/xss.svg"))
                        .header(HttpHeaders.AUTHORIZATION, bobBasic)
                        .PUT(HttpRequest.BodyPublishers.ofString("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, svgPutRes.statusCode());
        HttpResponse<String> svgGetRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/webdav-dir/xss.svg"))
                        .header(HttpHeaders.AUTHORIZATION, bobBasic)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, svgGetRes.statusCode());
        assertEquals("sandbox", svgGetRes.headers().firstValue("Content-Security-Policy").orElse(""));
        assertEquals("nosniff", svgGetRes.headers().firstValue("X-Content-Type-Options").orElse(""));

        HttpResponse<String> moveRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/webdav-dir/hello.txt"))
                        .header(HttpHeaders.AUTHORIZATION, bobBasic)
                        .header("Destination", davBase + "/webdav-dir/moved.txt")
                        .method("MOVE", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, moveRes.statusCode());

        HttpResponse<String> delRes = http.send(
                HttpRequest.newBuilder(URI.create(davBase + "/webdav-dir/moved.txt"))
                        .header(HttpHeaders.AUTHORIZATION, bobBasic)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, delRes.statusCode());

        // 10. 桌面端本地目录同步（必须通过本机目录选择器授权凭证；普通用户伪造或无授权凭据一律拒绝）
        String shellSecret = Files.readString(DATA_DIR.resolve("desktop-shell.secret"), StandardCharsets.UTF_8).trim();

        // 10.1 拒绝直接传 localDir、伪造 grantId 或无凭据调用 shell-grant
        mvc.perform(post("/api/desktop/sync")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"localDir\":\"" + syncDir.toString().replace("\\", "\\\\") + "\",\"targetDir\":\"bad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));

        mvc.perform(post("/api/desktop/sync")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantId\":\"forged-grant\",\"targetDir\":\"bad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003));

        mvc.perform(post("/api/desktop/sync/shell-grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"localDir\":\"" + syncDir.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isForbidden());

        // 10.2 有效外壳凭据仍拦截 FileHub 数据目录
        mvc.perform(post("/api/desktop/sync/shell-grant")
                        .header("X-Desktop-Shell-Secret", shellSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"localDir\":\"" + DATA_DIR.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));

        // 10.3 合法业务目录签发一次性 grantId，绑定规则后立即作废
        MvcResult grantRes = mvc.perform(post("/api/desktop/sync/shell-grant")
                        .header("X-Desktop-Shell-Secret", shellSecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"localDir\":\"" + syncDir.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String grantId = objectMapper.readTree(grantRes.getResponse().getContentAsString())
                .get("data").get("grantId").asText();

        Files.writeString(syncDir.resolve("sync-note.txt"), "synced content", StandardCharsets.UTF_8);
        MvcResult syncAdd = mvc.perform(post("/api/desktop/sync")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantId\":\"" + grantId + "\",\"targetDir\":\"desktop-backup\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String ruleId = objectMapper.readTree(syncAdd.getResponse().getContentAsString())
                .get("data").get("id").asText();

        // 同一 grantId 不可重复使用
        mvc.perform(post("/api/desktop/sync")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantId\":\"" + grantId + "\",\"targetDir\":\"desktop-backup-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003));

        mvc.perform(post("/api/desktop/sync/" + ruleId + "/run")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/file/tree")
                        .param("dir", "desktop-backup")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].fileName").value("sync-note.txt"));

        assertNotNull(desktopSyncService);
    }

    private JsonNode register(String username, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("data");
    }

    private String uploadSmallFile(String token, String fileName, String dir, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String hash = HashUtil.sha256Hex(bytes);
        String relPath = dir.isEmpty() ? "" : dir + "/" + fileName;
        String initBody = objectMapper.writeValueAsString(Map.of(
                "fileName", fileName,
                "fileSize", bytes.length,
                "fileHash", hash,
                "relativePath", relPath));
        MvcResult initRes = mvc.perform(post("/api/file/init")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initBody))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode initData = objectMapper.readTree(initRes.getResponse().getContentAsString()).get("data");
        String fileId = initData.get("fileId").asText();
        if (!initData.get("instant").asBoolean()) {
            MockMultipartFile chunk = new MockMultipartFile("chunk", fileName, "application/octet-stream", bytes);
            mvc.perform(multipart("/api/file/chunk")
                            .file(chunk)
                            .param("fileId", fileId)
                            .param("index", "0")
                            .param("chunkHash", hash)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/file/" + fileId + "/complete")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }
        return fileId;
    }
}
