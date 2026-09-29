package com.acme.performance.performance.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.performance.service.PerformanceCaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/performance-cases")
public class PerformanceCaseController {
    private final PerformanceCaseService service;
    public PerformanceCaseController(PerformanceCaseService service){this.service=service;}
    @PostMapping("/bonus") ApiResponse<PerformanceCaseService.CaseView> bonus(CurrentUser actor,@Valid @RequestBody BonusRequest body,HttpServletRequest request){return ApiResponse.success(service.applyBonus(actor,body.occurredAt(),body.description(),body.attachmentIds(),requestId(request)),requestId(request));}
    @PostMapping("/deductions") ApiResponse<PerformanceCaseService.CaseView> deduction(CurrentUser actor,@Valid @RequestBody DeductionRequest body,HttpServletRequest request){return ApiResponse.success(service.createDeduction(actor,new PerformanceCaseService.DeductionInput(body.caseType(),body.targetUserId(),body.occurredAt(),body.description(),body.ruleCode(),body.score(),body.attachmentIds(),body.incidentId()),requestId(request)),requestId(request));}
    @PostMapping("/admonitions") ApiResponse<PerformanceCaseService.CaseView> admonition(CurrentUser actor,@Valid @RequestBody AdmonitionRequest body,HttpServletRequest request){return ApiResponse.success(service.createAdmonition(actor,body.targetUserId(),body.occurredAt(),body.description(),requestId(request)),requestId(request));}
    @GetMapping("/mine") ApiResponse<List<PerformanceCaseService.CaseView>> mine(CurrentUser actor,HttpServletRequest request){return ApiResponse.success(service.mine(actor),requestId(request));}
    @GetMapping("/records") ApiResponse<List<PerformanceCaseService.CaseView>> records(CurrentUser actor,@RequestParam String period,HttpServletRequest request){return ApiResponse.success(service.records(actor,period),requestId(request));}
    @GetMapping("/pending") ApiResponse<List<PerformanceCaseService.CaseView>> pending(CurrentUser actor,HttpServletRequest request){return ApiResponse.success(service.pending(actor),requestId(request));}
    @PostMapping("/{id}/actions") ApiResponse<PerformanceCaseService.CaseView> review(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody ReviewRequest body,HttpServletRequest request){return ApiResponse.success(service.review(actor,id,body.version(),body.decision(),body.score(),body.ruleCode(),body.comment(),requestId(request)),requestId(request));}
    @PostMapping("/{id}/resubmit") ApiResponse<PerformanceCaseService.CaseView> resubmit(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody ResubmitRequest body,HttpServletRequest request){return ApiResponse.success(service.resubmitBonus(actor,id,body.version(),body.description(),body.attachmentIds(),requestId(request)),requestId(request));}
    @PostMapping("/{id}/appeals") ApiResponse<PerformanceCaseService.AppealView> appeal(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody AppealRequest body,HttpServletRequest request){return ApiResponse.success(service.appeal(actor,id,body.description(),requestId(request)),requestId(request));}
    @GetMapping("/appeals/pending") ApiResponse<List<PerformanceCaseService.AppealView>> appeals(CurrentUser actor,HttpServletRequest request){return ApiResponse.success(service.pendingAppeals(actor),requestId(request));}
    @PostMapping("/appeals/{id}/votes") ApiResponse<PerformanceCaseService.AppealView> vote(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody VoteRequest body,HttpServletRequest request){return ApiResponse.success(service.vote(actor,id,body.decision(),body.comment(),requestId(request)),requestId(request));}
    private String requestId(HttpServletRequest r){return String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE));}
    public record BonusRequest(@NotNull OffsetDateTime occurredAt,@NotBlank @Size(max=2000) String description,@Size(max=9) List<UUID> attachmentIds){}
    public record DeductionRequest(@NotBlank String caseType,@NotNull UUID targetUserId,@NotNull OffsetDateTime occurredAt,@NotBlank @Size(max=2000) String description,String ruleCode,@NotNull @Positive Integer score,@Size(max=9) List<UUID> attachmentIds,UUID incidentId){}
    public record AdmonitionRequest(@NotNull UUID targetUserId,@NotNull OffsetDateTime occurredAt,@NotBlank @Size(max=2000) String description){}
    public record ReviewRequest(long version,@NotBlank String decision,Integer score,String ruleCode,@Size(max=1000) String comment){}
    public record ResubmitRequest(long version,@NotBlank @Size(max=2000) String description,@Size(max=9) List<UUID> attachmentIds){}
    public record AppealRequest(@NotBlank @Size(max=2000) String description){}
    public record VoteRequest(@NotBlank String decision,@Size(max=1000) String comment){}
}
