package com.acme.performance.admin.web;

import com.acme.performance.admin.service.RuleAdminService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/rule-versions")
public class RuleAdminController {
    private final RuleAdminService service;
    public RuleAdminController(RuleAdminService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<RuleAdminService.VersionView>> versions(CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(service.versions(user), requestId(request));
    }
    @GetMapping("/{id}/rules")
    ApiResponse<List<RuleAdminService.RuleView>> rules(CurrentUser user, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(service.rules(user, id), requestId(request));
    }
    @PostMapping
    ApiResponse<RuleAdminService.VersionView> create(CurrentUser user, @Valid @RequestBody VersionRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.createVersion(user, body.version(), requestId(request)), requestId(request));
    }
    @PostMapping("/{id}/rules")
    ApiResponse<UUID> addRule(CurrentUser user, @PathVariable UUID id, @RequestBody RuleAdminService.RuleInput body, HttpServletRequest request) {
        return ApiResponse.success(service.addRule(user, id, body, requestId(request)), requestId(request));
    }
    @DeleteMapping("/{versionId}/rules/{ruleId}")
    ApiResponse<Void> deleteRule(CurrentUser user, @PathVariable UUID versionId, @PathVariable UUID ruleId, HttpServletRequest request) {
        service.deleteRule(user, versionId, ruleId, requestId(request)); return ApiResponse.success(null, requestId(request));
    }
    @PostMapping("/{id}/business-approval")
    ApiResponse<Void> approve(CurrentUser user, @PathVariable UUID id, HttpServletRequest request) {
        service.businessApprove(user, id, requestId(request)); return ApiResponse.success(null, requestId(request));
    }
    @PostMapping("/{id}/publish")
    ApiResponse<Void> publish(CurrentUser user, @PathVariable UUID id, @RequestBody(required=false) PublishRequest body, HttpServletRequest request) {
        service.publish(user, id, body == null ? null : body.effectiveAt(), requestId(request)); return ApiResponse.success(null, requestId(request));
    }
    private String requestId(HttpServletRequest request) { return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)); }
    public record VersionRequest(@NotBlank String version) {}
    public record PublishRequest(OffsetDateTime effectiveAt) {}
}
