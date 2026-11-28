package com.yuzhuo.filehub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuzhuo.filehub.auth.DownloadTicketService;
import com.yuzhuo.filehub.auth.OpaqueToken;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.config.ShareProperties;
import com.yuzhuo.filehub.dto.CreateShareRequest;
import com.yuzhuo.filehub.dto.DownloadTicketResponse;
import com.yuzhuo.filehub.dto.FileVO;
import com.yuzhuo.filehub.dto.PageResponse;
import com.yuzhuo.filehub.dto.ResetShareCodeResponse;
import com.yuzhuo.filehub.dto.ShareCreatedResponse;
import com.yuzhuo.filehub.dto.ShareMetaResponse;
import com.yuzhuo.filehub.dto.ShareSaveRequest;
import com.yuzhuo.filehub.dto.ShareVO;
import com.yuzhuo.filehub.dto.ShareVerifyResponse;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.entity.FileShare;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.mapper.FileShareMapper;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 链接 + 4 位提取码分享。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>链接里的 token 只是定位符，真正拦住人的是提取码（BCrypt 摘要）；</li>
 *   <li>匿名校验成功后复用 {@link DownloadTicketService} 签发 5 分钟票据，
 *       下载逻辑（Range / 多段 / 流式）完全不用重写；</li>
 *   <li>每分享连续输错达到阈值就锁定一段时间，和按 IP 的限流是两层独立防线。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareService {

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_EXPIRED = "EXPIRED";
    public static final String STATE_CANCELLED = "CANCELLED";
    public static final String STATE_FILE_MISSING = "FILE_MISSING";
    public static final String STATE_LOCKED = "LOCKED";
    public static final String STATE_EXHAUSTED = "EXHAUSTED";

    /** 去掉 0/O/1/I 等易混字符，共 32 个 */
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 4;
    private static final Pattern CODE_PATTERN =
            Pattern.compile("^[" + CODE_ALPHABET + "]{" + CODE_LENGTH + "}$");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");
    private static final Set<Integer> ALLOWED_EXPIRY_DAYS = Set.of(0, 1, 7, 30);
    private static final int DEFAULT_EXPIRY_DAYS = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FileShareMapper shareMapper;

    private final FileInfoMapper fileInfoMapper;

    private final FileStorageService fileStorageService;

    private final UserAccountMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final DownloadTicketService ticketService;

    private final ShareProperties properties;

    private AuditService auditService;

    @Autowired(required = false)
    public void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    // ==================================================================
    // 登录后：创建 / 列表 / 取消 / 重置
    // ==================================================================

    public ShareCreatedResponse create(CreateShareRequest req, UserPrincipal user) {
        int days = req.expiresInDays() == null ? DEFAULT_EXPIRY_DAYS : req.expiresInDays();
        if (!ALLOWED_EXPIRY_DAYS.contains(days)) {
            throw new BizException(10001, "有效期只能是 0（永久）/ 1 / 7 / 30 天");
        }
        if (req.maxDownloads() != null && req.maxDownloads() < 1) {
            throw new BizException(10001, "最大允许提取次数至少为 1 次");
        }

        FileInfo info = fileStorageService.requireInfo(req.fileId(), user);
        // 管理员 requireInfo 能看到别人的文件，但票据必须以真实所有者的身份签发
        if (info.getOwnerId() == null || !info.getOwnerId().equals(user.id())) {
            throw new BizException(42007, "只有文件所有者可以创建或管理该分享");
        }
        if (fileStorageService.findDoneForShare(req.fileId()) == null) {
            throw new BizException(42005, "文件未完成或已失效，无法分享");
        }

        LocalDateTime now = LocalDateTime.now();
        int perFile = Math.max(1, properties.getMaxActivePerFile());
        if (shareMapper.countActiveForFile(user.id(), info.getFileId(), now) >= perFile) {
            throw new BizException(42008,
                    "该文件的有效分享已达上限（" + perFile + " 个），请先取消不用的分享");
        }
        int perOwner = Math.max(1, properties.getMaxActivePerOwner());
        if (shareMapper.countActiveForOwner(user.id(), now) >= perOwner) {
            throw new BizException(42008,
                    "有效分享总数已达上限（" + perOwner + " 个），请先取消不用的分享");
        }

        LocalDateTime expiresAt = days == 0 ? null : now.plusDays(days);
        String token = OpaqueToken.generate();
        boolean needCode = req.requireCode() == null || req.requireCode();
        String code = needCode ? randomCode() : "";

        FileShare share = new FileShare();
        share.setShareToken(token);
        share.setFileId(info.getFileId());
        share.setOwnerId(user.id());
        share.setCodeHash(needCode ? passwordEncoder.encode(protectedCode(code)) : "");
        share.setRequireCode(needCode ? 1 : 0);
        share.setExpiresAt(expiresAt);
        share.setStatus(FileShare.STATUS_ACTIVE);
        share.setDownloadCount(0L);
        share.setMaxDownloads(req.maxDownloads());
        share.setFailedAttempts(0);
        share.setAuthVersion(0);
        share.setCreateTime(now);
        share.setUpdateTime(now);
        shareMapper.insert(share);

        log.info("创建分享：file={} owner={} expiresAt={} requireCode={} maxDownloads={}",
                info.getFileId(), user.id(), expiresAt, needCode, req.maxDownloads());
        if (auditService != null) {
            auditService.record(user, "SHARE_CREATE", "SHARE", token, info.getFileName(), null);
        }
        return new ShareCreatedResponse(token, code, "/#/s/" + token, expiresAt, now,
                needCode, req.maxDownloads());
    }

    public PageResponse<ShareVO> listMine(int page, int size, UserPrincipal user) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));

        Page<FileShare> result = shareMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<FileShare>()
                        .eq(FileShare::getOwnerId, user.id())
                        .orderByDesc(FileShare::getId));

        List<FileShare> rows = result.getRecords();
        Map<String, FileInfo> files = loadFiles(rows.stream().map(FileShare::getFileId).toList());
        LocalDateTime now = LocalDateTime.now();
        List<ShareVO> items = new ArrayList<>(rows.size());
        for (FileShare row : rows) {
            FileInfo file = files.get(row.getFileId());
            items.add(new ShareVO(
                    row.getShareToken(),
                    row.getFileId(),
                    file == null ? null : file.getFileName(),
                    file == null ? null : file.getFileSize(),
                    row.getExpiresAt(),
                    stateOf(row, file, now),
                    downloads(row),
                    isLocked(row, now),
                    row.getLockedUntil(),
                    row.getCreateTime(),
                    needCode(row),
                    row.getMaxDownloads()));
        }
        return new PageResponse<>(items, result.getTotal(), safePage, safeSize);
    }

    public void cancel(String token, UserPrincipal user) {
        validateToken(token);
        int updated = shareMapper.cancel(token, user.id(), LocalDateTime.now());
        if (updated == 0) {
            // 不是自己的、已经取消、或 token 不存在，统一文案，避免探测
            throw new BizException(42001, "分享不存在或已取消");
        }
        FileShare share = shareMapper.findByToken(token);
        if (share != null && share.getId() != null) {
            ticketService.revokeByShareId(share.getId());
        }
        log.info("取消分享：{}（owner={}）", token, user.id());
    }

    public ResetShareCodeResponse resetCode(String token, UserPrincipal user) {
        validateToken(token);
        FileShare share = shareMapper.findByToken(token);
        if (share == null || !isActive(share)
                || share.getOwnerId() == null || !share.getOwnerId().equals(user.id())) {
            throw new BizException(42001, "分享不存在或已取消");
        }
        if (!needCode(share)) {
            throw new BizException(10001, "免提取码分享无需重置提取码");
        }

        String code = randomCode();
        LocalDateTime now = LocalDateTime.now();
        int updated = shareMapper.resetCode(token, user.id(),
                passwordEncoder.encode(protectedCode(code)), now);
        if (updated == 0) {
            throw new BizException(42001, "分享不存在或已取消");
        }
        ticketService.revokeByShareId(share.getId());
        log.info("重置分享提取码：{}（owner={}）", token, user.id());
        return new ResetShareCodeResponse(token, code, share.getExpiresAt());
    }

    // ==================================================================
    // 匿名：查状态 / 校验提取码 / 登录后转存
    // ==================================================================

    public ShareMetaResponse meta(String token) {
        validateToken(token);
        FileShare share = shareMapper.findByToken(token);
        LocalDateTime now = LocalDateTime.now();
        if (share == null || !isActive(share)) {
            throw new BizException(42001, "分享不存在或已取消");
        }
        if (isExpired(share, now)) {
            throw new BizException(42002, "分享已过期");
        }
        if (isExhausted(share)) {
            throw new BizException(42009, "该分享提取次数已达上限");
        }
        boolean locked = isLocked(share, now);
        return new ShareMetaResponse(
                locked ? STATE_LOCKED : STATE_ACTIVE,
                share.getExpiresAt(),
                locked,
                share.getLockedUntil(),
                needCode(share),
                share.getMaxDownloads(),
                downloads(share));
    }

    /**
     * 校验提取码（或免提取码直接提取）。成功后签发一张绑定「文件 + 创建者」的短期下载票据，
     * 返回现有下载地址，Range/断点续传行为与登录下载完全一致。
     */
    public ShareVerifyResponse verify(String token, String rawCode) {
        validateToken(token);
        FileShare share = shareMapper.findByToken(token);
        LocalDateTime now = LocalDateTime.now();
        if (share == null || !isActive(share)) {
            throw new BizException(42001, "分享不存在或已取消");
        }
        if (isExpired(share, now)) {
            throw new BizException(42002, "分享已过期");
        }

        AuthorizedShareAccess access = authorizeAndConsume(share, rawCode, now, "文件已失效，无法下载");
        DownloadTicketResponse ticket = ticketService.issue(
                share.getFileId(), UserPrincipal.from(access.owner()), share.getId(), access.authVersion());
        log.info("分享提取成功：share={} file={}", token, share.getFileId());
        return new ShareVerifyResponse(
                access.file().getFileId(),
                access.file().getFileName(),
                access.file().getFileSize(),
                ticket.url(),
                ticket.expiresAt(),
                share.getExpiresAt());
    }

    /** 一键转存分享文件到当前登录用户网盘（基于内容寻址零拷贝复用） */
    public FileVO saveToMySpace(String token, ShareSaveRequest req, UserPrincipal receiver) {
        validateToken(token);
        FileShare share = shareMapper.findByToken(token);
        LocalDateTime now = LocalDateTime.now();
        if (share == null || !isActive(share)) {
            throw new BizException(42001, "分享不存在或已取消");
        }
        if (isExpired(share, now)) {
            throw new BizException(42002, "分享已过期");
        }

        FileVO copy;
        FileInfo file = null;
        if (req != null && req.ticket() != null && !req.ticket().isBlank()) {
            UserPrincipal resolved = ticketService.resolveForShare(
                    share.getFileId(), share.getId(), req.ticket());
            if (resolved != null && resolved.id().equals(share.getOwnerId())) {
                // 已凭有效分享票据验证过（且票据在 verify 时已计过一次提取次数），避免重复扣减
                file = fileStorageService.findDoneForShare(share.getFileId());
                if (file == null) {
                    throw new BizException(42005, "文件已失效，无法转存");
                }
                copy = fileStorageService.createSharedCopy(file, receiver);
            } else {
                copy = saveWithFreshAuthorization(share, req.code(), now, receiver);
                file = fileStorageService.findDoneForShare(share.getFileId());
            }
        } else {
            copy = saveWithFreshAuthorization(share, req == null ? null : req.code(), now, receiver);
        }

        String fileName = file != null ? file.getFileName() : copy.fileName();
        if (auditService != null) {
            auditService.record(receiver, "SHARE_SAVE", "FILE", copy.fileId(),
                    fileName + " <- share:" + token, null);
        }
        log.info("分享转存成功：share={} receiver={} newFile={}", token, receiver.id(), copy.fileId());
        return copy;
    }

    private FileVO saveWithFreshAuthorization(FileShare share, String rawCode, LocalDateTime now,
                                              UserPrincipal receiver) {
        AuthorizedShareAccess access = authorizeAndConsume(share, rawCode, now, "文件已失效，无法转存");
        try {
            return fileStorageService.createSharedCopy(access.file(), receiver);
        } catch (RuntimeException e) {
            // 接收者配额不足等情况未完成转存，退还刚扣除的提取次数
            shareMapper.refundDownload(share.getId(), LocalDateTime.now());
            throw e;
        }
    }

    private record AuthorizedShareAccess(FileInfo file, UserAccount owner, int authVersion) {
    }

    private AuthorizedShareAccess authorizeAndConsume(FileShare share, String rawCode,
                                                      LocalDateTime now, String invalidMsg) {
        if (isExhausted(share)) {
            throw new BizException(42009, "该分享提取次数已达上限");
        }
        if (needCode(share)) {
            verifyCodeWithLock(share, rawCode, now);
        }

        // 文件/所有者状态放在验码之后判断，避免只拿链接不用码就能探测文件是否失效
        FileInfo file = fileStorageService.findDoneForShare(share.getFileId());
        if (file == null) {
            throw new BizException(42005, invalidMsg);
        }
        UserAccount owner = userMapper.selectById(share.getOwnerId());
        if (owner == null || !owner.isActive()) {
            // 票据兑换时也会拒绝禁用用户，这里提前给出明确结果
            throw new BizException(42005, invalidMsg);
        }

        int authVersion = DownloadTicketService.versionOf(share.getAuthVersion());
        LocalDateTime consumeAt = LocalDateTime.now();
        // 计数带过期、取消、版本与次数上限兜底：验码期间分享刚好失效或重置时不能放行
        if (shareMapper.incrementDownload(share.getId(), authVersion, consumeAt) == 0) {
            FileShare after = shareMapper.selectById(share.getId());
            if (after == null || !isActive(after)) {
                throw new BizException(42001, "分享不存在或已取消");
            }
            if (isExpired(after, consumeAt)) {
                throw new BizException(42002, "分享已过期");
            }
            if (isExhausted(after)) {
                throw new BizException(42009, "该分享提取次数已达上限");
            }
            throw new BizException(42001, "分享不存在或已取消");
        }
        return new AuthorizedShareAccess(file, owner, authVersion);
    }

    private void verifyCodeWithLock(FileShare share, String rawCode, LocalDateTime now) {
        if (isLocked(share, now)) {
            throw lockException(share, now);
        }
        if (share.getLockedUntil() != null) {
            // 锁定已到期：先清零，避免这次输错直接又锁一轮
            shareMapper.clearExpiredLock(share.getId(), now);
        }

        String code = normalizeCode(rawCode);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new BizException(42006, "提取码应为 4 位字母或数字");
        }

        int maxAttempts = Math.max(1, properties.getCodeMaxAttempts());
        LocalDateTime lockUntil = now.plusMinutes(Math.max(1, properties.getCodeLockMinutes()));

        if (shareMapper.reserveAttempt(share.getId(), maxAttempts, lockUntil, now) == 0) {
            FileShare after = shareMapper.selectById(share.getId());
            if (after == null || !isActive(after)) {
                throw new BizException(42001, "分享不存在或已取消");
            }
            if (isLocked(after, now)) {
                throw lockException(after, now);
            }
            throw new BizException(42001, "分享不存在或已取消");
        }

        if (!passwordEncoder.matches(protectedCode(code), share.getCodeHash())) {
            FileShare after = shareMapper.selectById(share.getId());
            if (after == null || !isActive(after)) {
                throw new BizException(42001, "分享不存在或已取消");
            }
            if (isLocked(after, now)) {
                throw lockException(after, now);
            }
            int failed = after.getFailedAttempts() == null ? maxAttempts : after.getFailedAttempts();
            int remaining = Math.max(0, maxAttempts - failed);
            throw new BizException(42003, "提取码错误，还可尝试 " + remaining + " 次");
        }

        shareMapper.clearFailures(share.getId(), now);
    }

    // ==================================================================

    private Map<String, FileInfo> loadFiles(List<String> fileIds) {
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        Map<String, FileInfo> files = new HashMap<>();
        for (FileInfo info : fileInfoMapper.selectList(
                new LambdaQueryWrapper<FileInfo>().in(FileInfo::getFileId, fileIds))) {
            files.put(info.getFileId(), info);
        }
        return files;
    }

    private String stateOf(FileShare share, FileInfo file, LocalDateTime now) {
        if (!isActive(share)) {
            return STATE_CANCELLED;
        }
        if (file == null || file.getStatus() == null || file.getStatus() != FileInfo.STATUS_DONE) {
            return STATE_FILE_MISSING;
        }
        if (isExpired(share, now)) {
            return STATE_EXPIRED;
        }
        if (isExhausted(share)) {
            return STATE_EXHAUSTED;
        }
        return STATE_ACTIVE;
    }

    private static boolean needCode(FileShare share) {
        return share.getRequireCode() == null || share.getRequireCode() == 1;
    }

    private static long downloads(FileShare share) {
        return share.getDownloadCount() == null ? 0L : share.getDownloadCount();
    }

    private static boolean isExhausted(FileShare share) {
        return share.getMaxDownloads() != null && downloads(share) >= share.getMaxDownloads();
    }

    private boolean isExpired(FileShare share, LocalDateTime now) {
        return share.getExpiresAt() != null && !share.getExpiresAt().isAfter(now);
    }

    private boolean isLocked(FileShare share, LocalDateTime now) {
        return share.getLockedUntil() != null && share.getLockedUntil().isAfter(now);
    }

    private BizException lockException(FileShare share, LocalDateTime now) {
        long seconds = Duration.between(now, share.getLockedUntil()).getSeconds();
        long minutes = Math.max(1, (seconds + 59) / 60);
        return new BizException(42004, "尝试次数过多，请 " + minutes + " 分钟后再试");
    }

    private void validateToken(String token) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            throw new BizException(42001, "分享不存在或已取消");
        }
    }

    /** 提取码统一去空格、转大写；生成时只用大写字母表 */
    static String normalizeCode(String rawCode) {
        return rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 4 位码空间只有 32^4 ≈ 100 万；如果只做 BCrypt，拿到数据库的人离线穷举很快。
     * 配置了 pepper 时先做 HMAC，密钥只存在于进程环境，数据库泄漏不足以还原码空间。
     *
     * <p>未配置时原样返回，兼容 v8 之前的分享数据。</p>
     */
    private String protectedCode(String code) {
        String pepper = properties.getCodePepper();
        if (pepper == null || pepper.isBlank()) {
            return code;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // 只有 JVM 不支持 HmacSHA256 才会到这里（JDK 17 不可能），直接失败比降级成明文摘要安全
            throw new IllegalStateException("无法计算提取码摘要", e);
        }
    }

    static String randomCode() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            chars[i] = CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length()));
        }
        return new String(chars);
    }

    /** status=1 才算有效；null 一律视为不可用 */
    private static boolean isActive(FileShare share) {
        return share.getStatus() != null && share.getStatus() == FileShare.STATUS_ACTIVE;
    }
}
