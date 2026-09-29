package com.acme.performance.incident.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.incident.service.IncidentDossierService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/incidents")
public class IncidentDossierController {
    private final IncidentDossierService service;
    public IncidentDossierController(IncidentDossierService service){this.service=service;}

    @GetMapping("/{id}/dossier")
    ApiResponse<IncidentDossierService.DossierView> get(CurrentUser user,@PathVariable UUID id,HttpServletRequest request){return ApiResponse.success(service.get(user,id),requestId(request));}

    @GetMapping("/dossiers/open")
    ApiResponse<List<IncidentDossierService.DossierSummary>> open(CurrentUser user,HttpServletRequest request){return ApiResponse.success(service.openDossiers(user),requestId(request));}

    @PutMapping("/{id}/dossier/metadata")
    ApiResponse<IncidentDossierService.DossierView> metadata(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody MetadataRequest body,HttpServletRequest request){return ApiResponse.success(service.updateMetadata(user,id,new IncidentDossierService.MetadataInput(body.severity(),body.categoryCode(),body.impactLevel(),body.downtimeMinutes(),body.impactDescription(),body.performanceRequired()),body.version(),requestId(request)),requestId(request));}

    @PostMapping("/{id}/dossier/evidence")
    ApiResponse<IncidentDossierService.DossierView> evidence(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody EvidenceRequest body,HttpServletRequest request){return ApiResponse.success(service.addEvidence(user,id,body.attachmentId(),body.evidenceType(),body.description(),requestId(request)),requestId(request));}

    @PutMapping("/{id}/dossier/investigation")
    ApiResponse<IncidentDossierService.DossierView> investigation(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody InvestigationRequest body,HttpServletRequest request){return ApiResponse.success(service.saveInvestigation(user,id,new IncidentDossierService.InvestigationInput(body.mode(),body.directCause(),body.rootCause(),body.rootCauseCategory(),body.fiveWhys(),body.conclusion()),body.version(),requestId(request)),requestId(request));}

    @PostMapping("/{id}/dossier/investigation/confirm")
    ApiResponse<IncidentDossierService.DossierView> confirmInvestigation(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody VersionRequest body,HttpServletRequest request){return ApiResponse.success(service.confirmInvestigation(user,id,body.version(),requestId(request)),requestId(request));}

    @PostMapping("/{id}/dossier/responsibilities")
    ApiResponse<IncidentDossierService.DossierView> responsibility(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody ResponsibilityRequest body,HttpServletRequest request){return ApiResponse.success(service.addResponsibility(user,id,new IncidentDossierService.ResponsibilityInput(body.responsibleUserId(),body.responsibleOrgId(),body.responsibilityType(),body.responsibilityPercent(),body.basis(),body.proposedAction()),requestId(request)),requestId(request));}

    @PostMapping("/{id}/dossier/actions")
    ApiResponse<IncidentDossierService.DossierView> action(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody ActionRequest body,HttpServletRequest request){return ApiResponse.success(service.addAction(user,id,new IncidentDossierService.CorrectiveActionInput(body.actionType(),body.content(),body.ownerId(),body.dueAt()),requestId(request)),requestId(request));}

    @PostMapping("/dossier/actions/{actionId}/complete")
    ApiResponse<IncidentDossierService.DossierView> complete(CurrentUser user,@PathVariable UUID actionId,@Valid @RequestBody CompletionRequest body,HttpServletRequest request){return ApiResponse.success(service.completeAction(user,actionId,body.version(),body.note(),requestId(request)),requestId(request));}

    @PostMapping("/dossier/actions/{actionId}/accept")
    ApiResponse<IncidentDossierService.DossierView> accept(CurrentUser user,@PathVariable UUID actionId,@Valid @RequestBody AcceptanceRequest body,HttpServletRequest request){return ApiResponse.success(service.acceptAction(user,actionId,body.version(),body.decision(),body.comment(),requestId(request)),requestId(request));}

    @PostMapping("/{id}/dossier/archive")
    ApiResponse<IncidentDossierService.DossierView> archive(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody VersionRequest body,HttpServletRequest request){return ApiResponse.success(service.archive(user,id,body.version(),requestId(request)),requestId(request));}

    @PostMapping("/{id}/dossier/relations")
    ApiResponse<IncidentDossierService.DossierView> relate(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody RelationRequest body,HttpServletRequest request){return ApiResponse.success(service.relate(user,id,body.relatedIncidentId(),body.relationType(),requestId(request)),requestId(request));}

    @PostMapping("/{id}/dossier/reopen")
    ApiResponse<IncidentDossierService.DossierView> reopen(CurrentUser user,@PathVariable UUID id,@Valid @RequestBody ReopenRequest body,HttpServletRequest request){return ApiResponse.success(service.reopen(user,id,body.version(),body.reason(),requestId(request)),requestId(request));}

    private String requestId(HttpServletRequest request){return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));}
    public record MetadataRequest(long version,@NotBlank String severity,@NotBlank @Size(max=64) String categoryCode,@NotBlank String impactLevel,@PositiveOrZero int downtimeMinutes,@Size(max=2000) String impactDescription,boolean performanceRequired){}
    public record EvidenceRequest(@NotNull UUID attachmentId,@NotBlank @Size(max=32) String evidenceType,@Size(max=1000) String description){}
    public record InvestigationRequest(Long version,@NotBlank String mode,@NotBlank @Size(max=2000) String directCause,@NotBlank @Size(max=2000) String rootCause,@NotBlank @Size(max=64) String rootCauseCategory,Object fiveWhys,@NotBlank @Size(max=2000) String conclusion){}
    public record ResponsibilityRequest(UUID responsibleUserId,UUID responsibleOrgId,@NotBlank @Size(max=32) String responsibilityType,@Min(0) @Max(100) Integer responsibilityPercent,@NotBlank @Size(max=2000) String basis,@Size(max=1000) String proposedAction){}
    public record ActionRequest(@NotBlank @Size(max=32) String actionType,@NotBlank @Size(max=2000) String content,@NotNull UUID ownerId,@NotNull @Future OffsetDateTime dueAt){}
    public record VersionRequest(long version){}
    public record CompletionRequest(long version,@NotBlank @Size(max=2000) String note){}
    public record AcceptanceRequest(long version,@NotBlank String decision,@Size(max=1000) String comment){}
    public record RelationRequest(@NotNull UUID relatedIncidentId,@NotBlank String relationType){}
    public record ReopenRequest(long version,@NotBlank @Size(max=1000) String reason){}
}
