package com.acme.performance.auth.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class MeController {
    @GetMapping
    ApiResponse<CurrentUser> me(CurrentUser currentUser, HttpServletRequest request) {
        return ApiResponse.success(currentUser, String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
