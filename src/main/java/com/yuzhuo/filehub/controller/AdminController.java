package com.yuzhuo.filehub.controller;

import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.dto.IntegrityRepairRequest;
import com.yuzhuo.filehub.dto.IntegrityRepairResponse;
import com.yuzhuo.filehub.dto.IntegrityScanResponse;
import com.yuzhuo.filehub.service.IntegrityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员接口。整个 /api/admin/** 在 SecurityConfig 里要求 ROLE_ADMIN，
 * 普通用户拿不到这里的方法。
 */
@RestController
@RequestMapping("/api/admin/integrity")
@RequiredArgsConstructor
@Tag(name = "管理员", description = "完整性扫描与修复，需要 ROLE_ADMIN")
public class AdminController {

    private final IntegrityService integrityService;

    /**
     * @param deep  是否复算 SHA-256（默认 false，只查存在性和大小）
     * @param limit 本次最多检查多少条 DONE 记录（1–1000）
     */
    @GetMapping("/scan")
    public Result<IntegrityScanResponse> scan(
            @RequestParam(value = "deep", defaultValue = "false") boolean deep,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return Result.ok(integrityService.scan(deep, limit));
    }

    @PostMapping("/repair")
    public Result<IntegrityRepairResponse> repair(@RequestBody @Valid IntegrityRepairRequest req,
                                                  @AuthenticationPrincipal UserPrincipal admin) {
        return Result.ok(integrityService.repair(req, admin));
    }
}
