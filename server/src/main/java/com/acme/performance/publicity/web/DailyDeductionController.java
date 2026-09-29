package com.acme.performance.publicity.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.publicity.service.DailyDeductionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/publicity/daily-deductions")
public class DailyDeductionController {
    private final DailyDeductionService service;
    public DailyDeductionController(DailyDeductionService service){this.service=service;}
    @GetMapping
    ApiResponse<DailyDeductionService.Board> board(CurrentUser user,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,HttpServletRequest request){
        return ApiResponse.success(service.board(user,date,page,size),String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
