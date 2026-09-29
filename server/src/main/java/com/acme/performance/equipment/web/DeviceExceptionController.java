package com.acme.performance.equipment.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.equipment.service.DeviceExceptionExportService;
import com.acme.performance.equipment.service.DeviceExceptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Deprecated(forRemoval = false)
@RequestMapping("/api/device-exceptions")
public class DeviceExceptionController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final DeviceExceptionService service;
    private final DeviceExceptionExportService exportService;

    public DeviceExceptionController(DeviceExceptionService service, DeviceExceptionExportService exportService) {
        this.service = service;
        this.exportService = exportService;
    }

    @PostMapping
    ApiResponse<DeviceExceptionService.ExceptionView> create(CurrentUser actor,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody SaveRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(service.create(actor, key, body.equipmentId(), body.occurredOn(),
                body.phenomenon(), body.handlingMethod(), body.rootCause(), body.longTermAction(), body.submit()), requestId(request));
    }

    @PutMapping("/{id}")
    ApiResponse<DeviceExceptionService.ExceptionView> update(CurrentUser actor, @PathVariable UUID id,
            @Valid @RequestBody UpdateRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.update(actor, id, body.version(), body.equipmentId(), body.occurredOn(),
                body.phenomenon(), body.handlingMethod(), body.rootCause(), body.longTermAction(), body.submit()), requestId(request));
    }

    @GetMapping("/mine")
    ApiResponse<List<DeviceExceptionService.ExceptionView>> mine(CurrentUser actor, HttpServletRequest request) {
        return ApiResponse.success(service.mine(actor), requestId(request));
    }

    @GetMapping("/pending")
    ApiResponse<List<DeviceExceptionService.ExceptionView>> pending(CurrentUser actor, HttpServletRequest request) {
        return ApiResponse.success(service.pending(actor), requestId(request));
    }

    @PostMapping("/{id}/actions")
    ApiResponse<DeviceExceptionService.ExceptionView> review(CurrentUser actor, @PathVariable UUID id,
            @Valid @RequestBody ReviewRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.review(actor, id, body.version(), body.action(), body.comment(), requestId(request)), requestId(request));
    }

    @GetMapping("/archive")
    ApiResponse<List<DeviceExceptionService.ExceptionView>> archive(CurrentUser actor,
            @RequestParam(required = false) List<UUID> deviceIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "500") int limit, HttpServletRequest request) {
        return ApiResponse.success(service.archive(actor, deviceIds, from, to, keyword, limit), requestId(request));
    }

    @GetMapping("/export.xlsx")
    ResponseEntity<byte[]> export(CurrentUser actor, @RequestParam List<UUID> deviceIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] content = exportService.export(service.exportRows(actor, deviceIds, from, to));
        String filename = "设备异常档案_" + (from == null ? "全部" : from) + "_" + (to == null ? "全部" : to) + ".xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(content);
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    public record SaveRequest(UUID equipmentId, LocalDate occurredOn,
                              @NotBlank @Size(max = 2000) String phenomenon,
                              @NotBlank @Size(max = 2000) String handlingMethod,
                              @NotBlank @Size(max = 2000) String rootCause,
                              @Size(max = 2000) String longTermAction, boolean submit) {}
    public record UpdateRequest(long version, UUID equipmentId, LocalDate occurredOn,
                                @NotBlank @Size(max = 2000) String phenomenon,
                                @NotBlank @Size(max = 2000) String handlingMethod,
                                @NotBlank @Size(max = 2000) String rootCause,
                                @Size(max = 2000) String longTermAction, boolean submit) {}
    public record ReviewRequest(long version, @NotBlank String action, @Size(max = 1000) String comment) {}
}
