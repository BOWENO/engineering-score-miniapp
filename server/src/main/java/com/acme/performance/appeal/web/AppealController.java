package com.acme.performance.appeal.web;

import com.acme.performance.appeal.service.AppealService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Deprecated(forRemoval = false)
@RequestMapping("/api/appeals")
public class AppealController {
    private final AppealService service;
    public AppealController(AppealService service) { this.service = service; }

    @PostMapping
    ApiResponse<UUID> submit(CurrentUser user, @RequestBody SubmitRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.submit(user, body.resultId(), body.reasonCode(), body.description()), requestId(request));
    }
    @GetMapping("/mine")
    ApiResponse<List<AppealService.AppealView>> mine(CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(service.mine(user), requestId(request));
    }
    @GetMapping("/pending")
    ApiResponse<List<AppealService.AppealView>> pending(CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(service.pending(user), requestId(request));
    }
    @PostMapping("/{id}/actions")
    ApiResponse<Void> decide(CurrentUser user, @PathVariable UUID id, @RequestBody DecisionRequest body, HttpServletRequest request) {
        service.decide(user, id, body.decision(), body.comment(), requestId(request)); return ApiResponse.success(null, requestId(request));
    }
    private String requestId(HttpServletRequest request) { return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)); }
    public record SubmitRequest(UUID resultId, @NotBlank String reasonCode, @NotBlank String description) {}
    public record DecisionRequest(@NotBlank String decision, @NotBlank String comment) {}
}
