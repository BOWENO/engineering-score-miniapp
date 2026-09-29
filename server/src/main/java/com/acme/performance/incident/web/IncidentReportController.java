package com.acme.performance.incident.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.incident.service.IncidentReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/incident-reports")
public class IncidentReportController {
    private final IncidentReportService service;
    public IncidentReportController(IncidentReportService service){this.service=service;}
    @GetMapping("/monthly") ApiResponse<IncidentReportService.MonthlyReport> monthly(CurrentUser user,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,HttpServletRequest request){return ApiResponse.success(service.monthly(user,from,to),requestId(request));}
    @GetMapping("/repeat-candidates") ApiResponse<List<IncidentReportService.RepeatCandidate>> repeats(CurrentUser user,@RequestParam(defaultValue="90") int days,HttpServletRequest request){return ApiResponse.success(service.repeatCandidates(user,days),requestId(request));}
    private String requestId(HttpServletRequest request){return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));}
}
