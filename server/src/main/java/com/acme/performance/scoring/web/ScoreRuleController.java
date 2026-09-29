package com.acme.performance.scoring.web;

import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.scoring.model.ScoreRuleView;
import com.acme.performance.scoring.service.ScoreRuleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/score-rules")
public class ScoreRuleController {
    private final ScoreRuleService service;
    public ScoreRuleController(ScoreRuleService service) { this.service = service; }

    @GetMapping
    ApiResponse<List<ScoreRuleView>> list(@RequestParam(required = false) String type, @RequestParam(required=false) java.time.LocalDate date, HttpServletRequest request) {
        return ApiResponse.success(service.activeRules(type,date==null?java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")):date), String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
