package com.acme.performance.equipment.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.equipment.service.EquipmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {
    private final EquipmentService service;

    public EquipmentController(EquipmentService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<EquipmentService.EquipmentView>> active(CurrentUser actor, HttpServletRequest request) {
        return ApiResponse.success(service.active(actor), String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
