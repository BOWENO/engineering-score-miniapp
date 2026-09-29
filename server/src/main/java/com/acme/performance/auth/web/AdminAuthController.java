package com.acme.performance.auth.web;

import com.acme.performance.auth.service.AdminAuthService;
import com.acme.performance.auth.service.TokenService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AdminAuthController {
    private final AdminAuthService service;
    public AdminAuthController(AdminAuthService service) { this.service = service; }
    @PostMapping({"/account/login", "/admin/login"})
    ApiResponse<TokenService.IssuedToken> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.login(body.username(), body.password(), body.clientType()), requestId(request));
    }
    @PostMapping("/admin/bootstrap")
    ApiResponse<TokenService.IssuedToken> bootstrap(@RequestHeader("X-Bootstrap-Token") String token,
            @Valid @RequestBody BootstrapRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.bootstrap(token, body.username(), body.password(), body.employeeNo(), body.displayName()), requestId(request));
    }
    @PostMapping({"/account/password", "/admin/password"})
    ApiResponse<TokenService.IssuedToken> changePassword(CurrentUser user, @Valid @RequestBody PasswordRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.changePassword(user.userId(), body.currentPassword(), body.newPassword()), requestId(request));
    }
    private String requestId(HttpServletRequest request) { return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)); }
    public record LoginRequest(@NotBlank @Size(max=64) String username, @NotBlank @Size(max=128) String password,
                               @Size(max=16) String clientType) {}
    public record BootstrapRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{3,64}") String username,
            @NotBlank @Size(min=12,max=128) String password, @NotBlank @Size(max=64) String employeeNo,
            @NotBlank @Size(max=128) String displayName) {}
    public record PasswordRequest(@NotBlank @Size(max=128) String currentPassword,
                                  @NotBlank @Size(min=8,max=128) String newPassword) {}
}
