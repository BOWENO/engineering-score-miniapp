package com.acme.performance.health;

import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {
    @GetMapping
    ApiResponse<Map<String, Object>> health(HttpServletRequest request) {
        return ApiResponse.success(
                Map.of("status", "UP", "service", "engineering-score-server", "time", Instant.now()),
                String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
