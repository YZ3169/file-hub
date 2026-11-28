package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.dto.DownloadTicketResponse;
import com.yuzhuo.filehub.entity.DownloadTicket;
import com.yuzhuo.filehub.entity.FileShare;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.DownloadTicketMapper;
import com.yuzhuo.filehub.mapper.FileShareMapper;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 浏览器 &lt;a download&gt; 带不了 Authorization 头，所以先换一张短期票据。
 * 票据只存哈希、绑定 fileId + userId，并再次校验用户仍然存在且可用。
 *
 * <p>分享签发的票据额外绑定 {@code shareId + auth_version}：兑换时重新核对分享仍有效、
 * 未过期且授权版本未变。取消分享或重置提取码会推进版本，哪怕并发的提取请求
 * 在撤销之后才把票据写进库，这张票据也兑换不了。</p>
 */
@Service
@RequiredArgsConstructor
public class DownloadTicketService {

    private final DownloadTicketMapper ticketMapper;

    private final UserAccountMapper userMapper;

    private final AuthProperties properties;

    private final FileShareMapper shareMapper;

    public DownloadTicketResponse issue(String fileId, UserPrincipal user) {
        return issue(fileId, user, null, null);
    }

    public DownloadTicketResponse issue(String fileId, UserPrincipal user, Long shareId) {
        return issue(fileId, user, shareId, 0);
    }

    public DownloadTicketResponse issue(String fileId, UserPrincipal user, Long shareId, Integer shareVersion) {
        String raw = OpaqueToken.generate();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(Math.max(1, properties.getTicketTtlMinutes()));

        DownloadTicket ticket = new DownloadTicket();
        ticket.setTicketHash(OpaqueToken.hash(raw));
        ticket.setFileId(fileId);
        ticket.setUserId(user.id());
        ticket.setShareId(shareId);
        ticket.setShareVersion(shareId == null ? null : versionOf(shareVersion));
        ticket.setExpiresAt(expiresAt);
        ticket.setCreatedAt(now);
        ticketMapper.insert(ticket);

        String url = "/api/file/" + fileId + "/download?ticket=" + raw;
        return new DownloadTicketResponse(raw, expiresAt, url);
    }

    /** 取消或重置分享时，立即删除该分享签发过的票据（清理用；真正的失效靠授权版本） */
    public int revokeByShareId(Long shareId) {
        if (shareId == null) {
            return 0;
        }
        return ticketMapper.deleteByShareId(shareId);
    }

    /**
     * 兑换票据。无效、过期、文件不匹配、用户被禁用、所属分享已失效都返回 null。
     */
    public UserPrincipal resolve(String fileId, String rawTicket) {
        if (rawTicket == null || rawTicket.isBlank()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        DownloadTicket ticket = ticketMapper.findValid(OpaqueToken.hash(rawTicket), fileId, now);
        if (ticket == null || !shareStillAuthorizes(ticket, now)) {
            return null;
        }
        return activeUser(ticket);
    }

    /** 校验严格绑定到指定 shareId 的下载票据，防止跨分享或普通下载票据混淆绕过提取码/次数限制 */
    public UserPrincipal resolveForShare(String fileId, Long shareId, String rawTicket) {
        if (rawTicket == null || rawTicket.isBlank() || shareId == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        DownloadTicket ticket = ticketMapper.findValid(OpaqueToken.hash(rawTicket), fileId, now);
        if (ticket == null || ticket.getShareId() == null || !ticket.getShareId().equals(shareId)
                || !shareStillAuthorizes(ticket, now)) {
            return null;
        }
        return activeUser(ticket);
    }

    private UserPrincipal activeUser(DownloadTicket ticket) {
        UserAccount user = userMapper.selectById(ticket.getUserId());
        if (user == null || !user.isActive()) {
            return null;
        }
        return UserPrincipal.from(user);
    }

    /**
     * 普通票据直接通过；分享票据必须满足：分享仍存在且有效、未过期、指向同一文件与同一所有者，
     * 且票据签发时的授权版本等于当前版本。
     */
    private boolean shareStillAuthorizes(DownloadTicket ticket, LocalDateTime now) {
        if (ticket.getShareId() == null) {
            return true;
        }
        FileShare share = shareMapper.selectById(ticket.getShareId());
        if (share == null || share.getStatus() == null || share.getStatus() != FileShare.STATUS_ACTIVE) {
            return false;
        }
        if (share.getExpiresAt() != null && !share.getExpiresAt().isAfter(now)) {
            return false;
        }
        if (!Objects.equals(share.getFileId(), ticket.getFileId())
                || !Objects.equals(share.getOwnerId(), ticket.getUserId())) {
            return false;
        }
        return versionOf(share.getAuthVersion()) == versionOf(ticket.getShareVersion());
    }

    /** 升级前的数据没有版本列值，按 0 处理 */
    public static int versionOf(Integer version) {
        return version == null ? 0 : version;
    }
}
