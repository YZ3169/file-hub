package com.yuzhuo.filehub.controller;

import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.dto.CreateShareRequest;
import com.yuzhuo.filehub.dto.PageResponse;
import com.yuzhuo.filehub.dto.ResetShareCodeResponse;
import com.yuzhuo.filehub.dto.ShareCreatedResponse;
import com.yuzhuo.filehub.dto.ShareVO;
import com.yuzhuo.filehub.service.ShareService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录后的分享管理。匿名提取走 {@link PublicShareController}，
 * 两个前缀分开可以避免 permitAll 通配符误放行管理接口。
 */
@RestController
@RequestMapping("/api/share")
@RequiredArgsConstructor
@Tag(name = "分享", description = "创建 / 我的分享 / 取消 / 重置提取码")
public class ShareController {

    private final ShareService shareService;

    @PostMapping
    public Result<ShareCreatedResponse> create(@Valid @RequestBody CreateShareRequest request,
                                               @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(shareService.create(request, user));
    }

    @GetMapping("/mine")
    public Result<PageResponse<ShareVO>> mine(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(shareService.listMine(page, size, user));
    }

    @DeleteMapping("/{token}")
    public Result<Void> cancel(@PathVariable String token,
                               @AuthenticationPrincipal UserPrincipal user) {
        shareService.cancel(token, user);
        return Result.ok();
    }

    @PostMapping("/{token}/reset-code")
    public Result<ResetShareCodeResponse> resetCode(@PathVariable String token,
                                                    @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(shareService.resetCode(token, user));
    }
}
