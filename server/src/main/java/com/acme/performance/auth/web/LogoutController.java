package com.acme.performance.auth.web;

import com.acme.performance.auth.service.TokenService;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LogoutController {
    private final TokenService tokenService;
    public LogoutController(TokenService tokenService) { this.tokenService = tokenService; }

    @PostMapping("/api/auth/logout")
    ApiResponse<Void> logout(@RequestHeader(value="Authorization", required=false) String authorization,
                             HttpServletRequest request) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        tokenService.revoke(token);
        return ApiResponse.success(null, String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
