package com.yuzhuo.filehub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.dto.PageResponse;
import com.yuzhuo.filehub.entity.AuditLog;
import com.yuzhuo.filehub.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public void record(UserPrincipal user, String action, String targetType,
                       String targetId, String detail, String clientIp) {
        record(user == null ? null : user.id(),
                user == null ? null : user.username(),
                action, targetType, targetId, detail, clientIp);
    }

    public void record(Long userId, String username, String action, String targetType,
                       String targetId, String detail, String clientIp) {
        try {
            AuditLog row = new AuditLog();
            row.setUserId(userId);
            row.setUsername(truncate(username, 64));
            row.setAction(truncate(action, 64));
            row.setTargetType(truncate(targetType, 32));
            row.setTargetId(truncate(targetId, 128));
            row.setDetail(truncate(detail, 512));
            row.setClientIp(truncate(clientIp, 64));
            row.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("写入审计日志失败 action={} target={}", action, targetId, e);
        }
    }

    public PageResponse<AuditLog> list(int page, int size, String action,
                                       UserPrincipal user, boolean allUsers) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        if (!user.isAdmin() || !allUsers) {
            qw.eq(AuditLog::getUserId, user.id());
        }
        if (action != null && !action.isBlank()) {
            qw.eq(AuditLog::getAction, action.trim());
        }
        qw.orderByDesc(AuditLog::getId);
        Page<AuditLog> res = auditLogMapper.selectPage(new Page<>(safePage, safeSize), qw);
        return new PageResponse<>(res.getRecords(), res.getTotal(), safePage, safeSize);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
