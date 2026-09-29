package com.acme.performance.application.web;

import com.acme.performance.application.service.ScoreApplicationService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Deprecated(forRemoval = false)
@RequestMapping("/api/score-applications")
public class ScoreApplicationController {
    private final ScoreApplicationService service;
    public ScoreApplicationController(ScoreApplicationService service) { this.service = service; }

    @PostMapping
    ApiResponse<ScoreApplicationService.ApplicationResult> create(CurrentUser user,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(service.create(user.userId(), key, body.ruleCode(), body.occurredAt(),
                body.description(), body.attachmentIds(), body.submit()), requestId(request));
    }

    @GetMapping("/mine")
    ApiResponse<List<ScoreApplicationService.ApplicationView>> mine(CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(service.mine(user.userId()), requestId(request));
    }

    @PostMapping("/{id}/submit")
    ApiResponse<ScoreApplicationService.ApplicationResult> submit(CurrentUser user, @PathVariable UUID id,
            @RequestBody SubmitRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.submitDraft(user.userId(), id, body.version()), requestId(request));
    }

    private String requestId(HttpServletRequest request) { return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)); }

    public record CreateRequest(@NotBlank String ruleCode, OffsetDateTime occurredAt,
                                @Size(max=2000) String description, List<UUID> attachmentIds, boolean submit) {}
    public record SubmitRequest(long version) {}
}
