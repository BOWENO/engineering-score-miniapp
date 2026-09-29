package com.acme.performance.equipment.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.equipment.service.EquipmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/equipment")
public class EquipmentAdminController {
    private final EquipmentService service;

    public EquipmentAdminController(EquipmentService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<EquipmentService.EquipmentView>> all(CurrentUser actor, HttpServletRequest request) {
        return ApiResponse.success(service.all(actor), requestId(request));
    }

    @PostMapping
    ApiResponse<EquipmentService.EquipmentView> create(CurrentUser actor, @Valid @RequestBody CreateRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success(service.create(actor, body.code(), body.name(), body.category(),
                body.orgUnitId(), body.lineId(), body.stationId(), requestId(request)), requestId(request));
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<EquipmentService.ImportResult> importEquipment(CurrentUser actor,
                                                                @RequestPart("file") MultipartFile file,
                                                                HttpServletRequest request) {
        return ApiResponse.success(service.importEquipment(actor, file, requestId(request)), requestId(request));
    }

    @PutMapping("/{id}")
    ApiResponse<EquipmentService.EquipmentView> update(CurrentUser actor, @PathVariable UUID id,
                                                        @Valid @RequestBody UpdateRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.success(service.update(actor, id, body.code(), body.name(), body.category(),
                body.orgUnitId(), body.lineId(), body.stationId(), body.status(), body.version(), requestId(request)), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    public record CreateRequest(@NotBlank String code, @NotBlank String name, @NotBlank String category,
                                UUID orgUnitId, UUID lineId, UUID stationId) {}
    public record UpdateRequest(@NotBlank String code, @NotBlank String name, @NotBlank String category,
                                UUID orgUnitId, UUID lineId, UUID stationId, @NotBlank String status, long version) {}
}
