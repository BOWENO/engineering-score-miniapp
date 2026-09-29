package com.acme.performance.task.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.task.service.WorkBenchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workbench")
public class WorkBenchController {
    private final WorkBenchService service;public WorkBenchController(WorkBenchService service){this.service=service;}
    @GetMapping ApiResponse<WorkBenchService.WorkBenchView> get(CurrentUser actor,HttpServletRequest request){return ApiResponse.success(service.get(actor),String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));}
}
