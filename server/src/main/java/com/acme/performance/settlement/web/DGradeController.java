package com.acme.performance.settlement.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.settlement.service.DGradeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/d-grades")
public class DGradeController {
    private final DGradeService service;
    public DGradeController(DGradeService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<DGradeService.NominationView>> nominations(CurrentUser user, @RequestParam String period, HttpServletRequest request) {
        return ApiResponse.success(service.nominations(user, period), requestId(request));
    }
    @GetMapping("/mine") ApiResponse<List<DGradeService.NominationView>> mine(CurrentUser user,HttpServletRequest request){return ApiResponse.success(service.mine(user),requestId(request));}

    @PostMapping
    ApiResponse<UUID> nominate(CurrentUser user, @RequestBody NominationRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.nominate(user, body.period(), body.userId(), body.reasonCode(), body.description(),
                body.evidenceAttachmentId(), requestId(request)), requestId(request));
    }
    @PostMapping("/{id}/confirmations")
    ApiResponse<DGradeService.ConfirmationResult> confirm(CurrentUser user, @PathVariable UUID id,
            @RequestBody ConfirmationRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.confirm(user, id, body.decision(), body.comment(), requestId(request)), requestId(request));
    }
    @PostMapping("/{id}/appeals") ApiResponse<DGradeService.AppealView> appeal(CurrentUser user,@PathVariable UUID id,@RequestBody AppealRequest body,HttpServletRequest request){return ApiResponse.success(service.appeal(user,id,body.description(),requestId(request)),requestId(request));}
    @GetMapping("/appeals/pending") ApiResponse<List<DGradeService.AppealView>> appeals(CurrentUser user,HttpServletRequest request){return ApiResponse.success(service.pendingAppeals(user),requestId(request));}
    @PostMapping("/appeals/{id}/votes") ApiResponse<DGradeService.AppealView> vote(CurrentUser user,@PathVariable UUID id,@RequestBody ConfirmationRequest body,HttpServletRequest request){return ApiResponse.success(service.voteAppeal(user,id,body.decision(),body.comment(),requestId(request)),requestId(request));}
    private String requestId(HttpServletRequest request) { return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)); }
    public record NominationRequest(@NotBlank String period, UUID userId, @NotBlank String reasonCode,
                                    @NotBlank String description, UUID evidenceAttachmentId) {}
    public record ConfirmationRequest(@NotBlank String decision, String comment) {}
    public record AppealRequest(@NotBlank String description) {}
}
