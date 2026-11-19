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
import com.yuzhuo.filehub.dto.PageResponse;
import com.yuzhuo.filehub.dto.ResetShareCodeResponse;
import com.yuzhuo.filehub.dto.ShareCreatedResponse;
import com.yuzhuo.filehub.dto.ShareMetaResponse;
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

    // ==================================================================
    // 登录后：创建 / 列表 / 取消 / 重置
    // ==================================================================

    public ShareCreatedResponse create(CreateShareRequest req, UserPrincipal user) {
        int days = req.expiresInDays() == null ? DEFAULT_EXPIRY_DAYS : req.expiresInDays();
        if (!ALLOWED_EXPIRY_DAYS.contains(days)) {
            throw new BizException(10001, "有效期只能是 0（永久）/ 1 / 7 / 30 天");
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
        String code = randomCode();

        FileShare share = new FileShare();
        share.setShareToken(token);
        share.setFileId(info.getFileId());
        share.setOwnerId(user.id());
        share.setCodeHash(passwordEncoder.encode(protectedCode(code)));
        share.setExpiresAt(expiresAt);
        share.setStatus(FileShare.STATUS_ACTIVE);
        share.setDownloadCount(0L);
        share.setFailedAttempts(0);
        share.setCreateTime(now);
        share.setUpdateTime(now);
        shareMapper.insert(share);

        log.info("创建分享：file={} owner={} expiresAt={}", info.getFileId(), user.id(), expiresAt);
        return new ShareCreatedResponse(token, code, "/#/s/" + token, expiresAt, now);
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
                    row.getDownloadCount() == null ? 0 : row.getDownloadCount(),
                    isLocked(row, now),
                    row.getLockedUntil(),
                    row.getCreateTime()));
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
        log.info("取消分享：{}（owner={}）", token, user.id());
    }

    public ResetShareCodeResponse resetCode(String token, UserPrincipal user) {
        validateToken(token);
        FileShare share = shareMapper.findByToken(token);
        if (share == null || !isActive(share)
                || share.getOwnerId() == null || !share.getOwnerId().equals(user.id())) {
            throw new BizException(42001, "分享不存在或已取消");
        }

        String code = randomCode();
        LocalDateTime now = LocalDateTime.now();
        int updated = shareMapper.resetCode(token, user.id(),
                passwordEncoder.encode(protectedCode(code)), now);
        if (updated == 0) {
            throw new BizException(42001, "分享不存在或已取消");
        }
        log.info("重置分享提取码：{}（owner={}）", token, user.id());
        return new ResetShareCodeResponse(token, code, share.getExpiresAt());
    }

    // ==================================================================
    // 匿名：查状态 / 校验提取码
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
        boolean locked = isLocked(share, now);
        return new ShareMetaResponse(
                locked ? STATE_LOCKED : STATE_ACTIVE,
                share.getExpiresAt(),
                locked,
                share.getLockedUntil());
    }

    /**
     * 校验提取码。成功后签发一张绑定「文件 + 创建者」的短期下载票据，
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

        // 先原子地占一次尝试额度，再比对提取码。顺序反过来会让并发请求全部先跑完
        // BCrypt，实际被测试的码数远超阈值。
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

        // 码是对的：这一次不该被算成失败尝试（文件即使失效也不能把用户锁死）
        shareMapper.clearFailures(share.getId(), now);

        // 文件/所有者状态放在验码之后判断，避免只拿链接不用码就能探测文件是否失效
        FileInfo file = fileStorageService.findDoneForShare(share.getFileId());
        if (file == null) {
            throw new BizException(42005, "文件已失效，无法下载");
        }
        UserAccount owner = userMapper.selectById(share.getOwnerId());
        if (owner == null || !owner.isActive()) {
            // 票据兑换时也会拒绝禁用用户，这里提前给出明确结果
            throw new BizException(42005, "文件已失效，无法下载");
        }

        // 计数带过期兜底：验码期间分享刚好过期时不能签发票据
        if (shareMapper.incrementDownload(share.getId(), now) == 0) {
            FileShare after = shareMapper.selectById(share.getId());
            if (after == null || !isActive(after)) {
                throw new BizException(42001, "分享不存在或已取消");
            }
            if (isExpired(after, LocalDateTime.now())) {
                throw new BizException(42002, "分享已过期");
            }
            throw new BizException(42001, "分享不存在或已取消");
        }
        DownloadTicketResponse ticket = ticketService.issue(share.getFileId(), UserPrincipal.from(owner));
        log.info("分享提取成功：share={} file={}", token, share.getFileId());
        return new ShareVerifyResponse(
                file.getFileId(),
                file.getFileName(),
                file.getFileSize(),
                ticket.url(),
                ticket.expiresAt(),
                share.getExpiresAt());
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
        return STATE_ACTIVE;
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
