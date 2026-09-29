package com.acme.performance.task.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.task.model.DashboardView;
import com.acme.performance.task.service.DashboardService;
import com.acme.performance.task.service.TaskCompletionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Deprecated(forRemoval = false)
@RequestMapping("/api")
public class TaskController {
    private final DashboardService dashboardService;
    private final TaskCompletionService completionService;

    public TaskController(DashboardService dashboardService, TaskCompletionService completionService) {
        this.dashboardService = dashboardService;
        this.completionService = completionService;
    }

    @GetMapping("/me/dashboard")
    ApiResponse<DashboardView> dashboard(CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(dashboardService.get(user.userId()), requestId(request));
    }

    @PostMapping("/daily-tasks/{taskId}/complete")
    ApiResponse<TaskCompletionService.CompletionResult> complete(
            CurrentUser user, @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CompletionRequest body, HttpServletRequest request) {
        return ApiResponse.success(completionService.complete(user.userId(), taskId, idempotencyKey,
                body.version(), body.submittedValue(), body.description()), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    public record CompletionRequest(long version, @Size(max=512) String submittedValue, @Size(max=2000) String description) {}
}
