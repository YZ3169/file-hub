package com.yuzhuo.filehub.controller;

import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.dto.ShareMetaResponse;
import com.yuzhuo.filehub.dto.ShareVerifyRequest;
import com.yuzhuo.filehub.dto.ShareVerifyResponse;
import com.yuzhuo.filehub.service.ShareService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名提取接口：不需要登录，只有持有完整链接的人才能访问。
 * 查询状态不泄露文件名；校验提取码成功后才返回文件信息与短期票据。
 */
@RestController
@RequestMapping("/api/public/share")
@RequiredArgsConstructor
@Tag(name = "匿名分享", description = "链接状态 / 提取码校验")
public class PublicShareController {

    private final ShareService shareService;

    @GetMapping("/{token}")
    public Result<ShareMetaResponse> meta(@PathVariable String token) {
        return Result.ok(shareService.meta(token));
    }

    @PostMapping("/{token}/verify")
    public Result<ShareVerifyResponse> verify(@PathVariable String token,
                                              @Valid @RequestBody ShareVerifyRequest request) {
        return Result.ok(shareService.verify(token, request.code()));
    }
}
