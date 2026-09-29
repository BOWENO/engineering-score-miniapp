package com.acme.performance.auth.web;

import com.acme.performance.auth.service.DevIdentityService;
import com.acme.performance.auth.service.TokenService;
import com.acme.performance.task.service.DailyTaskGenerator;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile({"local", "test"})
@RestController
@RequestMapping("/api/auth/dev-login")
public class DevLoginController {
    private final DevIdentityService identityService;
    private final TokenService tokenService;
    private final DailyTaskGenerator taskGenerator;

    public DevLoginController(DevIdentityService identityService, TokenService tokenService, DailyTaskGenerator taskGenerator) {
        this.identityService = identityService;
        this.tokenService = tokenService;
        this.taskGenerator = taskGenerator;
    }

    @PostMapping
    ApiResponse<TokenService.IssuedToken> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        var userId = identityService.ensureUser(body.employeeNo(), body.displayName(), body.roleCode());
        taskGenerator.generate(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
        return ApiResponse.success(tokenService.issue(userId), String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }

    public record LoginRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,32}") String employeeNo,
            @NotBlank String displayName,
            @NotBlank @Pattern(regexp = "TECHNICIAN|ASSISTANT_ENGINEER|SUPERVISOR|DEPARTMENT_MANAGER") String roleCode) {}
}
