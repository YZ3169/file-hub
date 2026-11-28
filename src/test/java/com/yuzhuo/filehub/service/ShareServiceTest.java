package com.yuzhuo.filehub.service;

import com.yuzhuo.filehub.auth.DownloadTicketService;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.config.ShareProperties;
import com.yuzhuo.filehub.dto.CreateShareRequest;
import com.yuzhuo.filehub.dto.DownloadTicketResponse;
import com.yuzhuo.filehub.dto.ResetShareCodeResponse;
import com.yuzhuo.filehub.dto.ShareCreatedResponse;
import com.yuzhuo.filehub.dto.ShareVerifyResponse;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.entity.FileShare;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.mapper.FileShareMapper;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareServiceTest {

    private static final String TOKEN = "A".repeat(43);

    @Mock
    FileShareMapper shareMapper;

    @Mock
    FileInfoMapper fileInfoMapper;

    @Mock
    FileStorageService fileStorageService;

    @Mock
    UserAccountMapper userMapper;

    @Mock
    DownloadTicketService ticketService;

    ShareProperties properties;

    BCryptPasswordEncoder encoder;

    ShareService service;

    UserPrincipal user = new UserPrincipal(1L, "alice", "USER");

    @BeforeEach
    void setUp() {
        properties = new ShareProperties();
        // 测试里用最低 cost，别把 10 次 BCrypt 跑成慢测试
        encoder = new BCryptPasswordEncoder(4);
        service = new ShareService(shareMapper, fileInfoMapper, fileStorageService,
                userMapper, encoder, ticketService, properties);
        // 绝大多数用例不关心“占额度”这一步；默认放行，个别用例再覆盖。
        lenient().when(shareMapper.reserveAttempt(anyLong(), anyInt(), any(), any())).thenReturn(1);
    }

    // ---------------- 创建 ----------------

    @Test
    @DisplayName("创建：非所有者（含管理员）拒绝")
    void createRejectsNonOwner() {
        FileInfo file = file("f1", 2L, FileInfo.STATUS_DONE);
        when(fileStorageService.requireInfo("f1", user)).thenReturn(file);

        BizException e = assertThrows(BizException.class,
                () -> service.create(new CreateShareRequest("f1", 7), user));
        assertEquals(42007, e.getCode());
    }

    @Test
    @DisplayName("创建：上传中或物理文件缺失拒绝")
    void createRejectsUnfinishedFile() {
        when(fileStorageService.requireInfo("f1", user)).thenReturn(file("f1", 1L, FileInfo.STATUS_DONE));
        when(fileStorageService.findDoneForShare("f1")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.create(new CreateShareRequest("f1", 7), user));
        assertEquals(42005, e.getCode());
    }

    @Test
    @DisplayName("创建：只接受 0/1/7/30 天")
    void createRejectsUnknownExpiry() {
        BizException e = assertThrows(BizException.class,
                () -> service.create(new CreateShareRequest("f1", 2), user));
        assertEquals(10001, e.getCode());
    }

    @Test
    @DisplayName("创建：4 位码只存 BCrypt，默认 7 天")
    void createHashesCode() {
        FileInfo file = file("f1", 1L, FileInfo.STATUS_DONE);
        when(fileStorageService.requireInfo("f1", user)).thenReturn(file);
        when(fileStorageService.findDoneForShare("f1")).thenReturn(file);

        ShareCreatedResponse resp = service.create(new CreateShareRequest("f1", null), user);

        assertEquals(4, resp.code().length());
        assertTrue(resp.code().matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}"));
        assertEquals("/#/s/" + resp.token(), resp.sharePath());
        assertNotNull(resp.expiresAt());

        ArgumentCaptor<FileShare> captor = ArgumentCaptor.forClass(FileShare.class);
        verify(shareMapper).insert(captor.capture());
        FileShare saved = captor.getValue();
        assertEquals(resp.token(), saved.getShareToken());
        assertEquals("f1", saved.getFileId());
        assertEquals(1L, saved.getOwnerId());
        assertTrue(saved.getCodeHash().startsWith("$2"));
        assertTrue(encoder.matches(resp.code(), saved.getCodeHash()));
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now().plusDays(6)));
    }

    @Test
    @DisplayName("创建：单文件有效分享数量达到上限后拒绝")
    void createRejectsWhenFileQuotaReached() {
        properties.setMaxActivePerFile(2);
        FileInfo file = file("f1", 1L, FileInfo.STATUS_DONE);
        when(fileStorageService.requireInfo("f1", user)).thenReturn(file);
        when(fileStorageService.findDoneForShare("f1")).thenReturn(file);
        when(shareMapper.countActiveForFile(eq(1L), eq("f1"), any())).thenReturn(2);

        BizException e = assertThrows(BizException.class,
                () -> service.create(new CreateShareRequest("f1", 7), user));
        assertEquals(42008, e.getCode());
        assertTrue(e.getMessage().contains("该文件"));
        verify(shareMapper, never()).insert(any(FileShare.class));
    }

    @Test
    @DisplayName("创建：用户有效分享总数达到上限后拒绝，不再做 BCrypt")
    void createRejectsWhenOwnerQuotaReached() {
        properties.setMaxActivePerOwner(3);
        FileInfo file = file("f1", 1L, FileInfo.STATUS_DONE);
        when(fileStorageService.requireInfo("f1", user)).thenReturn(file);
        when(fileStorageService.findDoneForShare("f1")).thenReturn(file);
        when(shareMapper.countActiveForOwner(eq(1L), any())).thenReturn(3);

        BizException e = assertThrows(BizException.class,
                () -> service.create(new CreateShareRequest("f1", 7), user));
        assertEquals(42008, e.getCode());
        assertTrue(e.getMessage().contains("总数"));
        verify(shareMapper, never()).insert(any(FileShare.class));
    }

    // ---------------- 校验 ----------------

    @Test
    @DisplayName("校验：大小写/空格归一后正确码可通过，并以所有者身份签发票据")
    void verifyNormalizesAndIssuesTicket() {
        FileShare share = shareWithCode("ABCD");
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);
        when(fileStorageService.findDoneForShare("f1")).thenReturn(file("f1", 1L, FileInfo.STATUS_DONE));
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L));
        when(shareMapper.incrementDownload(eq(share.getId()), any())).thenReturn(1);
        when(ticketService.issue(eq("f1"), any(), eq(10L))).thenReturn(
                new DownloadTicketResponse("t", LocalDateTime.now().plusMinutes(5),
                        "/api/file/f1/download?ticket=t"));

        ShareVerifyResponse resp = service.verify(TOKEN, " abcd ");

        assertEquals("f1", resp.fileId());
        assertEquals("测试.bin", resp.fileName());
        assertEquals("/api/file/f1/download?ticket=t", resp.downloadUrl());
        verify(ticketService).issue(eq("f1"),
                argThat(p -> p.id() == 1L && !p.isAdmin()), eq(10L));
    }

    @Test
    @DisplayName("校验：配置 pepper 后，库里存的是 HMAC(code) 的 BCrypt，仍能正常提取")
    void verifyWorksWithPepper() {
        properties.setCodePepper("test-pepper");
        FileInfo file = file("f1", 1L, FileInfo.STATUS_DONE);
        when(fileStorageService.requireInfo("f1", user)).thenReturn(file);
        when(fileStorageService.findDoneForShare("f1")).thenReturn(file);

        ShareCreatedResponse created = service.create(new CreateShareRequest("f1", 7), user);
        ArgumentCaptor<FileShare> captor = ArgumentCaptor.forClass(FileShare.class);
        verify(shareMapper).insert(captor.capture());
        FileShare saved = captor.getValue();
        // 裸 4 位码匹配不上，说明 BCrypt 之前确实套了 HMAC
        assertFalse(encoder.matches(created.code(), saved.getCodeHash()));

        saved.setId(10L);
        when(shareMapper.findByToken(created.token())).thenReturn(saved);
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L));
        when(shareMapper.incrementDownload(eq(10L), any())).thenReturn(1);
        when(ticketService.issue(eq("f1"), any(), eq(10L))).thenReturn(
                new DownloadTicketResponse("t", LocalDateTime.now().plusMinutes(5),
                        "/api/file/f1/download?ticket=t"));

        ShareVerifyResponse resp = service.verify(created.token(), created.code().toLowerCase());

        assertEquals("f1", resp.fileId());
    }

    @Test
    @DisplayName("校验：错码计数，未达阈值返回剩余次数")
    void verifyWrongCodeCounts() {
        FileShare share = shareWithCode("ABCD");
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);
        FileShare after = shareWithCode("ABCD");
        after.setFailedAttempts(1);
        when(shareMapper.selectById(share.getId())).thenReturn(after);

        BizException e = assertThrows(BizException.class, () -> service.verify(TOKEN, "ZZZZ"));
        assertEquals(42003, e.getCode());
        assertTrue(e.getMessage().contains("9"));
        verify(ticketService, never()).issue(anyString(), any(), any());
    }

    @Test
    @DisplayName("校验：达到阈值后锁定，返回 42004")
    void verifyLocksAfterThreshold() {
        properties.setCodeMaxAttempts(2);
        FileShare share = shareWithCode("ABCD");
        share.setFailedAttempts(1);
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);
        FileShare after = shareWithCode("ABCD");
        after.setFailedAttempts(2);
        after.setLockedUntil(LocalDateTime.now().plusMinutes(15));
        when(shareMapper.selectById(share.getId())).thenReturn(after);

        BizException e = assertThrows(BizException.class, () -> service.verify(TOKEN, "ZZZZ"));
        assertEquals(42004, e.getCode());
    }

    @Test
    @DisplayName("校验：锁定期间不再比对提取码")
    void verifyLockedBeforeBcrypt() {
        FileShare share = shareWithCode("ABCD");
        share.setLockedUntil(LocalDateTime.now().plusMinutes(5));
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);

        BizException e = assertThrows(BizException.class, () -> service.verify(TOKEN, "ABCD"));
        assertEquals(42004, e.getCode());
        verify(fileStorageService, never()).findDoneForShare(anyString());
    }

    @Test
    @DisplayName("校验：并发下额度抢不到且已锁定，直接 42004，不再跑 BCrypt")
    void verifyStopsWhenAttemptNotReservedBecauseLocked() {
        FileShare share = shareWithCode("ABCD");
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);
        when(shareMapper.reserveAttempt(anyLong(), anyInt(), any(), any())).thenReturn(0);
        FileShare after = shareWithCode("ABCD");
        after.setFailedAttempts(10);
        after.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(shareMapper.selectById(share.getId())).thenReturn(after);

        BizException e = assertThrows(BizException.class, () -> service.verify(TOKEN, "ABCD"));
        assertEquals(42004, e.getCode());
        verify(fileStorageService, never()).findDoneForShare(anyString());
    }

    @Test
    @DisplayName("校验：额度抢不到是因为分享刚被取消，返回 42001")
    void verifyStopsWhenAttemptNotReservedBecauseCancelled() {
        FileShare share = shareWithCode("ABCD");
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);
        when(shareMapper.reserveAttempt(anyLong(), anyInt(), any(), any())).thenReturn(0);
        FileShare after = shareWithCode("ABCD");
        after.setStatus(FileShare.STATUS_CANCELLED);
        when(shareMapper.selectById(share.getId())).thenReturn(after);

        BizException e = assertThrows(BizException.class, () -> service.verify(TOKEN, "ABCD"));
        assertEquals(42001, e.getCode());
    }

    @Test
    @DisplayName("校验：验码期间分享刚好过期，不签发下载票据")
    void verifyDoesNotIssueTicketWhenExpiredMidFlight() {
        FileShare share = shareWithCode("ABCD");
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);
        when(fileStorageService.findDoneForShare("f1")).thenReturn(file("f1", 1L, FileInfo.STATUS_DONE));
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L));
        when(shareMapper.incrementDownload(eq(share.getId()), any())).thenReturn(0);
        FileShare after = shareWithCode("ABCD");
        after.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(shareMapper.selectById(share.getId())).thenReturn(after);

        BizException e = assertThrows(BizException.class, () -> service.verify(TOKEN, "ABCD"));
        assertEquals(42002, e.getCode());
        verify(ticketService, never()).issue(anyString(), any(), any());
    }

    @Test
    @DisplayName("校验：签发前按当前时间检查过期，不复用请求开始时间")
    void verifyChecksCurrentTimeBeforeIssuingTicket() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        LocalDateTime expiresAt = startedAt.plusSeconds(1);
        FileShare share = shareWithCode("ABCD");
        share.setExpiresAt(expiresAt);
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);
        when(fileStorageService.findDoneForShare("f1"))
                .thenReturn(file("f1", 1L, FileInfo.STATUS_DONE));
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L));
        // 模拟数据库 WHERE expires_at > now，旧代码传请求开始时间会错误放行。
        when(shareMapper.incrementDownload(eq(share.getId()), any())).thenAnswer(inv ->
                expiresAt.isAfter(inv.getArgument(1, LocalDateTime.class)) ? 1 : 0);
        lenient().when(shareMapper.selectById(share.getId())).thenReturn(share);
        lenient().when(ticketService.issue(eq("f1"), any(), eq(10L))).thenReturn(
                new DownloadTicketResponse("t", expiresAt.plusMinutes(5), "/download?ticket=t"));

        try (MockedStatic<LocalDateTime> clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            // 第一次读取在校验前；之后已经到达过期边界，不依赖真实睡眠。
            clock.when(LocalDateTime::now).thenReturn(startedAt, expiresAt);
            BizException e = assertThrows(BizException.class, () -> service.verify(TOKEN, "ABCD"));
            assertEquals(42002, e.getCode());
        }
        verify(ticketService, never()).issue(anyString(), any(), any());
    }

    @Test
    @DisplayName("校验：过期、取消、文件缺失、创建者禁用都不能下载")
    void verifyRejectsInvalidStates() {
        FileShare expired = shareWithCode("ABCD");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(shareMapper.findByToken("expired-token-1234567890")).thenReturn(expired);
        assertEquals(42002, assertThrows(BizException.class,
                () -> service.verify("expired-token-1234567890", "ABCD")).getCode());

        FileShare cancelled = shareWithCode("ABCD");
        cancelled.setStatus(FileShare.STATUS_CANCELLED);
        when(shareMapper.findByToken("cancelled-token-123456789")).thenReturn(cancelled);
        assertEquals(42001, assertThrows(BizException.class,
                () -> service.verify("cancelled-token-123456789", "ABCD")).getCode());

        FileShare missingFile = shareWithCode("ABCD");
        when(shareMapper.findByToken(TOKEN)).thenReturn(missingFile);
        when(fileStorageService.findDoneForShare("f1")).thenReturn(null);
        assertEquals(42005, assertThrows(BizException.class,
                () -> service.verify(TOKEN, "ABCD")).getCode());

        FileShare disabledOwner = shareWithCode("ABCD");
        when(shareMapper.findByToken("disabled-token-1234567890")).thenReturn(disabledOwner);
        when(fileStorageService.findDoneForShare("f1")).thenReturn(file("f1", 1L, FileInfo.STATUS_DONE));
        UserAccount disabled = activeUser(1L);
        disabled.setEnabled(0);
        when(userMapper.selectById(1L)).thenReturn(disabled);
        assertEquals(42005, assertThrows(BizException.class,
                () -> service.verify("disabled-token-1234567890", "ABCD")).getCode());
    }

    @Test
    @DisplayName("校验：格式不对返回 42006")
    void verifyRejectsBadFormat() {
        FileShare share = shareWithCode("ABCD");
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);

        BizException e = assertThrows(BizException.class, () -> service.verify(TOKEN, "O0I1"));
        assertEquals(42006, e.getCode());
    }

    // ---------------- 管理 ----------------

    @Test
    @DisplayName("重置：非所有者返回统一的不存在文案")
    void resetRejectsOtherOwner() {
        FileShare share = shareWithCode("ABCD");
        share.setOwnerId(2L);
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);

        BizException e = assertThrows(BizException.class, () -> service.resetCode(TOKEN, user));
        assertEquals(42001, e.getCode());
    }

    @Test
    @DisplayName("重置：新码只返回一次，库里存 BCrypt")
    void resetGeneratesNewCode() {
        FileShare share = shareWithCode("ABCD");
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);
        when(shareMapper.resetCode(eq(TOKEN), eq(1L), anyString(), any())).thenReturn(1);

        ResetShareCodeResponse resp = service.resetCode(TOKEN, user);

        assertEquals(4, resp.code().length());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(shareMapper).resetCode(eq(TOKEN), eq(1L), hash.capture(), any());
        assertTrue(encoder.matches(resp.code(), hash.getValue()));
        verify(ticketService).revokeByShareId(10L);
    }

    @Test
    @DisplayName("取消：更新 0 行按不存在处理；成功取消时立即吊销已签发票据")
    void cancelMissingShare() {
        when(shareMapper.cancel(eq(TOKEN), eq(1L), any())).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.cancel(TOKEN, user));
        assertEquals(42001, e.getCode());

        FileShare share = shareWithCode("ABCD");
        when(shareMapper.cancel(eq(TOKEN), eq(1L), any())).thenReturn(1);
        when(shareMapper.findByToken(TOKEN)).thenReturn(share);
        service.cancel(TOKEN, user);
        verify(ticketService).revokeByShareId(10L);
    }

    // ---------------- helpers ----------------

    private FileShare shareWithCode(String code) {
        LocalDateTime now = LocalDateTime.now();
        FileShare share = new FileShare();
        share.setId(10L);
        share.setShareToken(TOKEN);
        share.setFileId("f1");
        share.setOwnerId(1L);
        share.setCodeHash(encoder.encode(code));
        share.setStatus(FileShare.STATUS_ACTIVE);
        share.setDownloadCount(0L);
        share.setFailedAttempts(0);
        share.setCreateTime(now);
        share.setUpdateTime(now);
        return share;
    }

    private FileInfo file(String fileId, Long ownerId, int status) {
        FileInfo info = new FileInfo();
        info.setFileId(fileId);
        info.setFileName("测试.bin");
        info.setFileSize(1024L);
        info.setStatus(status);
        info.setOwnerId(ownerId);
        return info;
    }

    private UserAccount activeUser(Long id) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setUsername("alice");
        account.setRole(UserAccount.ROLE_USER);
        account.setEnabled(1);
        return account;
    }
}
