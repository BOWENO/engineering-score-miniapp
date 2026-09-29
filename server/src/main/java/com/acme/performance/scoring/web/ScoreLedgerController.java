package com.acme.performance.scoring.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.scoring.model.ScoreLedgerView;
import com.acme.performance.scoring.service.ScoreLedgerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/me/score-events")
public class ScoreLedgerController {
    private final ScoreLedgerService service;

    public ScoreLedgerController(ScoreLedgerService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<ScoreLedgerView> get(CurrentUser user, @RequestParam String period, HttpServletRequest request) {
        try {
            return ApiResponse.success(service.get(user.userId(), YearMonth.parse(period)),
                    String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
        } catch (DateTimeParseException ex) {
            throw new ApiException("INVALID_PERIOD", "月份格式应为 YYYY-MM", HttpStatus.BAD_REQUEST);
        }
    }
}
