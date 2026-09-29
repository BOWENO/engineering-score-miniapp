package com.acme.performance.publicity.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.publicity.service.PublicBoardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@Deprecated(forRemoval = false)
@RequestMapping("/api/public-board")
public class PublicBoardController {
    private final PublicBoardService service;
    public PublicBoardController(PublicBoardService service) { this.service = service; }

    @GetMapping
    ApiResponse<PublicBoardService.BoardView> board(CurrentUser user, @RequestParam String period,
            @RequestParam(defaultValue="false") boolean anonymous, HttpServletRequest request) {
        return ApiResponse.success(service.board(user, period, anonymous),
                String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
