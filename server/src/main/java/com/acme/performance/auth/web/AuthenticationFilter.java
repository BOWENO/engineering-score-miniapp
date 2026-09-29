package com.acme.performance.auth.web;

import com.acme.performance.auth.service.TokenService;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthenticationFilter extends OncePerRequestFilter {
    public static final String CURRENT_USER = "currentUser";
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    public AuthenticationFilter(TokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/health")
                || path.startsWith("/actuator/health")
                || path.equals("/api/auth/dev-login")
                || path.equals("/api/auth/account/login")
                || path.equals("/api/auth/admin/login")
                || path.equals("/api/auth/admin/bootstrap")
                || path.equals("/api/auth/wechat/login")
                || path.equals("/api/auth/wechat/bind-account");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        var currentUser = tokenService.authenticate(token);
        if (currentUser.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            String requestId = String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
            objectMapper.writeValue(response.getWriter(), ApiResponse.failure("UNAUTHORIZED", "登录已失效，请重新登录", requestId));
            return;
        }
        request.setAttribute(CURRENT_USER, currentUser.get());
        if (currentUser.get().passwordChangeRequired()
                && !pathAllowedBeforePasswordChange(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            String requestId = String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
            objectMapper.writeValue(response.getWriter(), ApiResponse.failure(
                    "PASSWORD_CHANGE_REQUIRED", "首次登录必须先修改密码", requestId));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean pathAllowedBeforePasswordChange(String path) {
        return path.equals("/api/me") || path.equals("/api/auth/account/password")
                || path.equals("/api/auth/admin/password") || path.equals("/api/auth/logout");
    }
}
