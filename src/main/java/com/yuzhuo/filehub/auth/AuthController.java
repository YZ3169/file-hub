package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.dto.AuthResponse;
import com.yuzhuo.filehub.dto.ChangePasswordRequest;
import com.yuzhuo.filehub.dto.LoginRequest;
import com.yuzhuo.filehub.dto.RegisterRequest;
import com.yuzhuo.filehub.dto.UserVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "注册 / 登录 / 当前用户 / 退出")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @GetMapping("/me")
    public Result<UserVO> me(@AuthenticationPrincipal UserPrincipal principal) {
        return Result.ok(authService.me(principal));
    }

    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(authorization);
        return Result.ok();
    }

    @PostMapping("/password")
    public Result<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @AuthenticationPrincipal UserPrincipal principal) {
        authService.changePassword(principal, request, TokenService.extractBearer(authorization));
        return Result.ok();
    }
}
