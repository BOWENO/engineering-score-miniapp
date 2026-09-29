package com.acme.performance.performance.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.performance.service.PerformanceAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/performance")
public class PerformanceAnalyticsController {
    private final PerformanceAnalyticsService service;public PerformanceAnalyticsController(PerformanceAnalyticsService service){this.service=service;}
    @GetMapping("/me") ApiResponse<PerformanceAnalyticsService.PersonalView> mine(CurrentUser actor,@RequestParam(required=false) String period,HttpServletRequest request){return ApiResponse.success(service.mine(actor,period),requestId(request));}
    @GetMapping("/overview") ApiResponse<PerformanceAnalyticsService.Overview> overview(CurrentUser actor,@RequestParam(required=false) String period,HttpServletRequest request){return ApiResponse.success(service.overview(actor,period),requestId(request));}
    private String requestId(HttpServletRequest r){return String.valueOf(r.getAttribute(RequestIdFilter.ATTRIBUTE));}
}
