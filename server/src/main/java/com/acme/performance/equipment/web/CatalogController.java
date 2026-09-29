package com.acme.performance.equipment.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.equipment.service.CatalogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final CatalogService service;
    public CatalogController(CatalogService service){this.service=service;}

    @GetMapping("/catalog")
    ApiResponse<CatalogService.CatalogView> active(CurrentUser actor,HttpServletRequest request){return ApiResponse.success(service.active(actor),requestId(request));}
    @GetMapping("/admin/catalog")
    ApiResponse<CatalogService.CatalogView> all(CurrentUser actor,HttpServletRequest request){return ApiResponse.success(service.all(actor),requestId(request));}
    @PostMapping("/admin/catalog/lines")
    ApiResponse<CatalogService.LineView> createLine(CurrentUser actor,@Valid @RequestBody LineRequest body,HttpServletRequest request){return ApiResponse.success(service.createLine(actor,body.teamId(),body.code(),body.name(),requestId(request)),requestId(request));}
    @PostMapping("/admin/catalog/stations")
    ApiResponse<CatalogService.StationView> createStation(CurrentUser actor,@Valid @RequestBody StationRequest body,HttpServletRequest request){return ApiResponse.success(service.createStation(actor,body.lineId(),body.code(),body.name(),requestId(request)),requestId(request));}
    @PutMapping("/admin/catalog/lines/{id}")
    ApiResponse<CatalogService.LineView> updateLine(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody CatalogUpdateRequest body,HttpServletRequest request){return ApiResponse.success(service.updateLine(actor,id,body.code(),body.name(),body.status(),body.version(),requestId(request)),requestId(request));}
    @PutMapping("/admin/catalog/stations/{id}")
    ApiResponse<CatalogService.StationView> updateStation(CurrentUser actor,@PathVariable UUID id,@Valid @RequestBody CatalogUpdateRequest body,HttpServletRequest request){return ApiResponse.success(service.updateStation(actor,id,body.code(),body.name(),body.status(),body.version(),requestId(request)),requestId(request));}
    @PutMapping("/admin/catalog/lines/{id}/status")
    ApiResponse<Void> lineStatus(CurrentUser actor,@PathVariable UUID id,@RequestBody StatusRequest body,HttpServletRequest request){service.setLineStatus(actor,id,body.status(),body.version(),requestId(request));return ApiResponse.success(null,requestId(request));}
    @PutMapping("/admin/catalog/stations/{id}/status")
    ApiResponse<Void> stationStatus(CurrentUser actor,@PathVariable UUID id,@RequestBody StatusRequest body,HttpServletRequest request){service.setStationStatus(actor,id,body.status(),body.version(),requestId(request));return ApiResponse.success(null,requestId(request));}
    @PostMapping("/admin/catalog/shifts")
    ApiResponse<CatalogService.ShiftView> shift(CurrentUser actor,@Valid @RequestBody ShiftRequest body,HttpServletRequest request){return ApiResponse.success(service.createShiftVersion(actor,body.code(),body.name(),body.startsAt(),body.endsAt(),body.effectiveFrom(),requestId(request)),requestId(request));}
    @PutMapping("/admin/calendar/{date}")
    ApiResponse<Void> calendar(CurrentUser actor,@PathVariable LocalDate date,@Valid @RequestBody CalendarRequest body,HttpServletRequest request){service.saveCalendarDay(actor,date,body.dayType(),body.name(),requestId(request));return ApiResponse.success(null,requestId(request));}

    private String requestId(HttpServletRequest r){return String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE));}
    public record LineRequest(UUID teamId,@NotBlank String code,@NotBlank String name){}
    public record StationRequest(UUID lineId,@NotBlank String code,@NotBlank String name){}
    public record CatalogUpdateRequest(@NotBlank String code,@NotBlank String name,@NotBlank String status,long version){}
    public record StatusRequest(@NotBlank String status,long version){}
    public record ShiftRequest(@NotBlank String code,@NotBlank String name,LocalTime startsAt,LocalTime endsAt,LocalDate effectiveFrom){}
    public record CalendarRequest(@NotBlank String dayType,@NotBlank String name){}
}
