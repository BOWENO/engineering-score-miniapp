package com.acme.performance.incident.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.incident.service.IncidentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {
    private final IncidentService service;public IncidentController(IncidentService service){this.service=service;}
    @PostMapping ApiResponse<IncidentService.IncidentView> create(CurrentUser actor,@Valid @RequestBody CreateRequest body,HttpServletRequest request){return ApiResponse.success(service.create(actor,body.occurredAt(),body.lineId(),body.stationId(),body.equipmentIds(),requestId(request)),requestId(request));}
    @GetMapping("/mine/pending") ApiResponse<List<IncidentService.IncidentView>> mine(CurrentUser actor,HttpServletRequest request){return ApiResponse.success(service.minePending(actor),requestId(request));}
    @GetMapping("/review-queue") ApiResponse<List<IncidentService.IncidentView>> queue(CurrentUser actor,HttpServletRequest request){return ApiResponse.success(service.reviewQueue(actor),requestId(request));}
    @GetMapping("/open") ApiResponse<List<IncidentService.IncidentView>> open(CurrentUser actor,HttpServletRequest request){return ApiResponse.success(service.openDossiers(actor),requestId(request));}
    @GetMapping("/{id}") ApiResponse<IncidentService.IncidentView> get(CurrentUser actor,@PathVariable UUID id,HttpServletRequest request){return ApiResponse.success(service.getVisible(actor,id),requestId(request));}
    @PutMapping("/{id}/statement") ApiResponse<IncidentService.IncidentView> statement(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody StatementRequest body,HttpServletRequest request){return ApiResponse.success(service.submitStatement(actor,id,body.phenomenon(),body.handlingMethod(),body.rootCause(),body.longTermAction(),body.version(),requestId(request)),requestId(request));}
    @PostMapping("/statements/{id}/review") ApiResponse<IncidentService.IncidentView> review(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody ReviewRequest body,HttpServletRequest request){return ApiResponse.success(service.reviewStatement(actor,id,body.version(),body.decision(),body.comment(),requestId(request)),requestId(request));}
    @PostMapping("/{id}/notes") ApiResponse<IncidentService.IncidentView> note(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody NoteRequest body,HttpServletRequest request){return ApiResponse.success(service.addNote(actor,id,body.content(),requestId(request)),requestId(request));}
    @PostMapping("/{id}/void") ApiResponse<IncidentService.IncidentView> voidIncident(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody VoidRequest body,HttpServletRequest request){return ApiResponse.success(service.voidIncident(actor,id,body.version(),body.reason(),requestId(request)),requestId(request));}
    @GetMapping("/archive") ApiResponse<List<IncidentService.IncidentView>> archive(CurrentUser actor,@RequestParam(required=false) List<UUID> equipmentIds,@RequestParam(required=false) UUID lineId,@RequestParam(required=false) UUID responsibleId,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="500") int limit,HttpServletRequest request){return ApiResponse.success(service.archive(actor,equipmentIds,lineId,responsibleId,from,to,keyword,limit),requestId(request));}
    @GetMapping("/search") ApiResponse<IncidentService.SearchResult> search(CurrentUser actor,@RequestParam(required=false) List<UUID> equipmentIds,@RequestParam(required=false) UUID lineId,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="ALL") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest request){return ApiResponse.success(service.search(actor,equipmentIds,lineId,from,to,keyword,status,page,size),requestId(request));}
    private String requestId(HttpServletRequest r){return String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE));}
    @PostMapping("/statements/{id}/follow-up")
    ApiResponse<IncidentService.IncidentView> followUp(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody FollowUpRequest body,HttpServletRequest request){return ApiResponse.success(service.followUp(actor,id,body.version(),body.responsibleUserId(),body.dueAt(),body.reason(),requestId(request)),requestId(request));}
    public record FollowUpRequest(long version,@NotNull UUID responsibleUserId,@NotNull @Future OffsetDateTime dueAt,@NotBlank @Size(max=1000) String reason){}
    public record CreateRequest(@NotNull OffsetDateTime occurredAt,@NotNull UUID lineId,@NotNull UUID stationId,@NotEmpty List<UUID> equipmentIds){}
    public record StatementRequest(long version,@NotBlank @Size(max=2000) String phenomenon,@NotBlank @Size(max=2000) String handlingMethod,@NotBlank @Size(max=2000) String rootCause,@Size(max=2000) String longTermAction){}
    public record ReviewRequest(long version,@NotBlank String decision,@Size(max=1000) String comment){}
    public record NoteRequest(@NotBlank @Size(max=2000) String content){}
    public record VoidRequest(long version,@NotBlank @Size(max=1000) String reason){}
}
