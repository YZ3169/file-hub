package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.dto.DownloadTicketResponse;
import com.yuzhuo.filehub.entity.DownloadTicket;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.DownloadTicketMapper;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 浏览器 &lt;a download&gt; 带不了 Authorization 头，所以先换一张短期票据。
 * 票据只存哈希、绑定 fileId + userId，并再次校验用户仍然存在且可用。
 */
@Service
@RequiredArgsConstructor
public class DownloadTicketService {

    private final DownloadTicketMapper ticketMapper;

    private final UserAccountMapper userMapper;

    private final AuthProperties properties;

    public DownloadTicketResponse issue(String fileId, UserPrincipal user) {
        String raw = OpaqueToken.generate();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(Math.max(1, properties.getTicketTtlMinutes()));

        DownloadTicket ticket = new DownloadTicket();
        ticket.setTicketHash(OpaqueToken.hash(raw));
        ticket.setFileId(fileId);
        ticket.setUserId(user.id());
        ticket.setExpiresAt(expiresAt);
        ticket.setCreatedAt(now);
        ticketMapper.insert(ticket);

        String url = "/api/file/" + fileId + "/download?ticket=" + raw;
        return new DownloadTicketResponse(raw, expiresAt, url);
    }

    /**
     * 兑换票据。无效、过期、文件不匹配、用户被禁用都返回 null。
     */
    public UserPrincipal resolve(String fileId, String rawTicket) {
        if (rawTicket == null || rawTicket.isBlank()) {
            return null;
        }
        DownloadTicket ticket = ticketMapper.findValid(
                OpaqueToken.hash(rawTicket), fileId, LocalDateTime.now());
        if (ticket == null) {
            return null;
        }
        UserAccount user = userMapper.selectById(ticket.getUserId());
        if (user == null || !user.isActive()) {
            return null;
        }
        return UserPrincipal.from(user);
    }
}
