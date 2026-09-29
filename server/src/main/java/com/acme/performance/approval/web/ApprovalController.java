package com.acme.performance.approval.web;

import com.acme.performance.approval.model.ApprovalView;
import com.acme.performance.approval.service.ApprovalService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Deprecated(forRemoval = false)
@RequestMapping("/api/approvals")
public class ApprovalController {
    private final ApprovalService service;
    public ApprovalController(ApprovalService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<ApprovalView>> pending(CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(service.pending(user), requestId(request));
    }

    @PostMapping("/{id}/actions")
    ApiResponse<ApprovalService.ActionResult> act(CurrentUser user, @PathVariable UUID id,
            @Valid @RequestBody ActionRequest body, HttpServletRequest request) {
        String requestId = requestId(request);
        return ApiResponse.success(service.act(user, id, body.version(), body.action(), body.reasonCode(), body.comment(), requestId), requestId);
    }

    private String requestId(HttpServletRequest request) { return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)); }
    public record ActionRequest(long version, @NotBlank String action, String reasonCode, @Size(max=1000) String comment) {}
}
