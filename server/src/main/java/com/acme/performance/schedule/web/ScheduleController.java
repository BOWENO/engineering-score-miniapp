package com.acme.performance.schedule.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.schedule.service.ScheduleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final ScheduleService service;
    public ScheduleController(ScheduleService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<ScheduleService.AssignmentView>> list(CurrentUser actor,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) UUID userId, @RequestParam(required=false) String status,
            HttpServletRequest request) {
        return ApiResponse.success(service.list(actor, from, to, userId, status), requestId(request));
    }

    @PostMapping("/batch")
    ApiResponse<ScheduleService.BatchResult> createBatch(CurrentUser actor, @Valid @RequestBody BatchRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.success(service.createBatch(actor, new ScheduleService.BatchRequest(body.from(),body.to(),
                body.shiftCode(),body.userIds(),body.lineId(),body.stationIds(),body.publish(),body.reason()), requestId(request)), requestId(request));
    }

    @PostMapping("/{id}/publish")
    ApiResponse<ScheduleService.AssignmentView> publish(CurrentUser actor, @PathVariable UUID id,
            @RequestBody VersionRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.publish(actor,id,body.version(),requestId(request)),requestId(request));
    }

    @PostMapping("/{id}/cancel")
    ApiResponse<ScheduleService.AssignmentView> cancel(CurrentUser actor, @PathVariable UUID id,
            @RequestBody CancelRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.cancel(actor,id,body.version(),body.reason(),requestId(request)),requestId(request));
    }

    @PostMapping("/{id}/acknowledge")
    ApiResponse<ScheduleService.AssignmentView> acknowledge(CurrentUser actor, @PathVariable UUID id,
                                                             HttpServletRequest request) {
        return ApiResponse.success(service.acknowledge(actor,id),requestId(request));
    }

    private String requestId(HttpServletRequest request) { return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)); }

    public record BatchRequest(@NotNull LocalDate from,@NotNull LocalDate to,@NotBlank String shiftCode,
                               @NotEmpty @Size(max=100) List<UUID> userIds,@NotNull UUID lineId,
                               @NotEmpty @Size(max=100) List<UUID> stationIds,boolean publish,@Size(max=1000) String reason) {}
    public record VersionRequest(long version) {}
    public record CancelRequest(long version,@Size(max=1000) String reason) {}
}
