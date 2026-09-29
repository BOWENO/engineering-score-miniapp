package com.acme.performance.settlement.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.settlement.service.SettlementCorrectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/settlement-corrections")
public class SettlementCorrectionController {
    private final SettlementCorrectionService service;

    public SettlementCorrectionController(SettlementCorrectionService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<SettlementCorrectionService.CorrectionView>> list(CurrentUser actor,
                                                                        @RequestParam String period,
                                                                        HttpServletRequest request) {
        return ApiResponse.success(service.list(actor, period), requestId(request));
    }

    @PostMapping
    ApiResponse<SettlementCorrectionService.CorrectionView> create(CurrentUser actor,
                                                                    @Valid @RequestBody CreateRequest body,
                                                                    HttpServletRequest request) {
        return ApiResponse.success(service.request(actor, body.runId(), body.userId(), body.adjustmentScore(),
                body.reason(), requestId(request)), requestId(request));
    }

    @PostMapping("/{id}/votes")
    ApiResponse<SettlementCorrectionService.CorrectionView> vote(CurrentUser actor, @PathVariable UUID id,
                                                                  @Valid @RequestBody VoteRequest body,
                                                                  HttpServletRequest request) {
        return ApiResponse.success(service.vote(actor, id, body.decision(), body.comment(), requestId(request)), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    public record CreateRequest(@NotNull UUID runId, @NotNull UUID userId, int adjustmentScore,
                                @NotBlank String reason) {}
    public record VoteRequest(@NotBlank String decision, String comment) {}
}
