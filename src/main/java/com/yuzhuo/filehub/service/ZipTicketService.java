package com.yuzhuo.filehub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuo.filehub.auth.AuthProperties;
import com.yuzhuo.filehub.auth.OpaqueToken;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.dto.ZipTicketResponse;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.entity.ZipTicket;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import com.yuzhuo.filehub.mapper.ZipTicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量/文件夹流式打包下载短期票据服务。
 */
@Service
@RequiredArgsConstructor
public class ZipTicketService {

    private final ZipTicketMapper zipTicketMapper;
    private final UserAccountMapper userMapper;
    private final AuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public record ZipItem(String fileId, String entryPath) {
    }

    public record ResolvedZipTicket(UserPrincipal user, String zipName, List<ZipItem> items) {
    }

    public ZipTicketResponse issue(UserPrincipal user, String zipName, List<ZipItem> items, long totalBytes) {
        if (items == null || items.isEmpty()) {
            throw new BizException(10001, "没有可打包的文件");
        }
        String raw = OpaqueToken.generate();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(Math.max(1, authProperties.getTicketTtlMinutes()));
        String payload;
        try {
            payload = objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("序列化打包清单失败", e);
        }

        ZipTicket row = new ZipTicket();
        row.setTicketHash(OpaqueToken.hash(raw));
        row.setUserId(user.id());
        row.setZipName(zipName);
        row.setPayload(payload);
        row.setExpiresAt(expiresAt);
        row.setCreatedAt(now);
        zipTicketMapper.insert(row);

        String downloadUrl = "/api/file/zip?ticket=" + raw;
        return new ZipTicketResponse(raw, expiresAt, downloadUrl, zipName, items.size(), totalBytes);
    }

    public ResolvedZipTicket resolve(String rawTicket) {
        if (rawTicket == null || rawTicket.isBlank()) {
            return null;
        }
        ZipTicket ticket = zipTicketMapper.findValid(OpaqueToken.hash(rawTicket), LocalDateTime.now());
        if (ticket == null) {
            return null;
        }
        UserAccount user = userMapper.selectById(ticket.getUserId());
        if (user == null || !user.isActive()) {
            return null;
        }
        List<ZipItem> items;
        try {
            items = objectMapper.readValue(ticket.getPayload(), new TypeReference<>() {
            });
        } catch (Exception e) {
            return null;
        }
        return new ResolvedZipTicket(UserPrincipal.from(user), ticket.getZipName(), items);
    }
}
