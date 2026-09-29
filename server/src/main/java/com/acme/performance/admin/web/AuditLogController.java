package com.acme.performance.admin.web;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/audit-logs")
public class AuditLogController {
    private final AuditLogService service;
    public AuditLogController(AuditLogService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<AuditLogService.AuditItem>> latest(CurrentUser user, @RequestParam(defaultValue="100") int limit,
                                                        HttpServletRequest request) {
        return ApiResponse.success(service.latest(user, limit), String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
