package com.acme.performance.auth.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.auth.service.TokenService;
import com.acme.performance.auth.service.WechatAuthService;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/wechat")
public class WechatLoginController {
    private final WechatAuthService authService;

    public WechatLoginController(WechatAuthService authService) { this.authService = authService; }

    @PostMapping("/login")
    ApiResponse<WechatAuthService.LoginResult> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return ApiResponse.success(authService.login(body.code()), String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }

    @PostMapping("/bind-account")
    ApiResponse<TokenService.IssuedToken> bindAccount(@Valid @RequestBody BindAccountRequest body, HttpServletRequest request) {
        return ApiResponse.success(authService.bindAccount(body.bindingToken(), body.username(), body.password()),
                String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }

    @PostMapping("/bind")
    ApiResponse<Void> bind(CurrentUser currentUser, @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        authService.bind(currentUser, body.code());
        return ApiResponse.success(null, String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }

    public record LoginRequest(@NotBlank @Size(max = 128) String code) {}
    public record BindAccountRequest(@NotBlank @Size(max=256) String bindingToken,
                                     @NotBlank @Size(max=64) String username,
                                     @NotBlank @Size(max=128) String password) {}
}
