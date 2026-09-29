package com.acme.performance.settlement.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.settlement.model.SettlementView;
import com.acme.performance.settlement.service.SettlementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {
    private final SettlementService service;
    public SettlementController(SettlementService service) { this.service = service; }

    @PostMapping("/preview")
    ApiResponse<SettlementView> preview(CurrentUser user, @RequestBody PreviewRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.preview(user, body.period(), body.orgUnitId(), body.version(), requestId(request)), requestId(request));
    }
    @GetMapping("/{id}")
    ApiResponse<SettlementView> view(CurrentUser user, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(service.view(user, id), requestId(request));
    }
    @GetMapping
    ApiResponse<List<SettlementView>> list(CurrentUser user,@RequestParam String period,HttpServletRequest request){return ApiResponse.success(service.list(user,period),requestId(request));}
    @PostMapping("/{id}/boundary-decisions")
    ApiResponse<SettlementView> resolve(CurrentUser user, @PathVariable UUID id, @RequestBody BoundaryRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.voteBoundary(user, id, body.userId(), body.grade(), body.reason(), requestId(request)), requestId(request));
    }
    @PostMapping("/{id}/confirmations") ApiResponse<SettlementView> confirm(CurrentUser user,@PathVariable UUID id,@RequestBody(required=false) ConfirmationRequest body,HttpServletRequest request){return ApiResponse.success(service.confirm(user,id,body==null?null:body.comment(),requestId(request)),requestId(request));}
    @PostMapping("/{id}/publish")
    ApiResponse<SettlementView> publish(CurrentUser user, @PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.success(service.publish(user, id, requestId(request)), requestId(request));
    }
    private String requestId(HttpServletRequest request) { return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)); }
    public record PreviewRequest(@NotBlank String period, UUID orgUnitId, @NotBlank String version) {}
    public record BoundaryRequest(UUID userId, @NotBlank String grade, @NotBlank String reason) {}
    public record ConfirmationRequest(String comment) {}
}
