package com.yuzhuo.filehub.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuzhuo.filehub.auth.TokenService;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.dto.AdminUserVO;
import com.yuzhuo.filehub.dto.PageResponse;
import com.yuzhuo.filehub.dto.UpdateQuotaRequest;
import com.yuzhuo.filehub.dto.UpdateUserStatusRequest;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import com.yuzhuo.filehub.service.AuditService;
import com.yuzhuo.filehub.service.QuotaService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员用户与配额管理（/api/admin/** 仅 ROLE_ADMIN 可访问）。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Tag(name = "管理员-用户管理", description = "用户列表 / 存储配额设置 / 账号启停")
public class AdminUsersController {

    private final UserAccountMapper userMapper;
    private final QuotaService quotaService;
    private final AuditService auditService;
    private final TokenService tokenService;

    @GetMapping
    public Result<PageResponse<AdminUserVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Page<UserAccount> res = userMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<UserAccount>().orderByAsc(UserAccount::getId));
        List<AdminUserVO> items = res.getRecords().stream()
                .map(u -> new AdminUserVO(
                        u.getId(),
                        u.getUsername(),
                        u.getRole(),
                        u.isActive(),
                        quotaService.quotaBytesFor(u.getId()),
                        quotaService.usedBytesFor(u.getId()),
                        u.getCreatedAt()))
                .toList();
        return Result.ok(new PageResponse<>(items, res.getTotal(), safePage, safeSize));
    }

    @PutMapping("/{userId}/quota")
    public Result<AdminUserVO> updateQuota(@PathVariable("userId") Long userId,
                                           @Valid @RequestBody UpdateQuotaRequest req,
                                           @AuthenticationPrincipal UserPrincipal admin,
                                           HttpServletRequest request) {
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(10005, "用户不存在：" + userId);
        }
        LocalDateTime now = LocalDateTime.now();
        userMapper.updateQuota(userId, req.quotaBytes(), now);
        auditService.record(admin, "QUOTA_UPDATE", "USER", String.valueOf(userId),
                user.getUsername() + " -> " + req.quotaBytes(), request.getRemoteAddr());
        UserAccount fresh = userMapper.selectById(userId);
        return Result.ok(new AdminUserVO(
                fresh.getId(),
                fresh.getUsername(),
                fresh.getRole(),
                fresh.isActive(),
                quotaService.quotaBytesFor(fresh.getId()),
                quotaService.usedBytesFor(fresh.getId()),
                fresh.getCreatedAt()));
    }

    @PutMapping("/{userId}/status")
    public Result<Void> updateStatus(@PathVariable("userId") Long userId,
                                     @Valid @RequestBody UpdateUserStatusRequest req,
                                     @AuthenticationPrincipal UserPrincipal admin,
                                     HttpServletRequest request) {
        if (admin.id().equals(userId) && !req.enabled()) {
            throw new BizException(10001, "不能禁用当前登录的管理员账号");
        }
        UserAccount user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(10005, "用户不存在：" + userId);
        }
        user.setEnabled(req.enabled() ? 1 : 0);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        if (!req.enabled()) {
            tokenService.revokeAllForUser(userId);
        }
        auditService.record(admin, req.enabled() ? "USER_ENABLE" : "USER_DISABLE",
                "USER", String.valueOf(userId), user.getUsername(), request.getRemoteAddr());
        return Result.ok();
    }
}
