package com.acme.performance.organization.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.organization.service.DirectoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/directory")
public class DirectoryController {
    private final DirectoryService service;public DirectoryController(DirectoryService service){this.service=service;}
    @GetMapping("/people") ApiResponse<List<DirectoryService.Person>> people(CurrentUser actor,HttpServletRequest request){return ApiResponse.success(service.people(actor),String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));}
}
