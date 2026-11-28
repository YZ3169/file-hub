package com.yuzhuo.filehub.controller;

import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.dto.PageResponse;
import com.yuzhuo.filehub.entity.AuditLog;
import com.yuzhuo.filehub.service.AuditService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@Tag(name = "审计日志", description = "登录、上传、删除、分享与 WebDAV 操作记录")
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/logs")
    public Result<PageResponse<AuditLog>> logs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String scope,
            @AuthenticationPrincipal UserPrincipal user) {
        boolean all = "all".equalsIgnoreCase(scope);
        return Result.ok(auditService.list(page, size, action, user, all));
    }
}
