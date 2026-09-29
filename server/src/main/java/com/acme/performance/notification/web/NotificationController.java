package com.acme.performance.notification.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.notification.service.NotificationService;
import com.acme.performance.notification.service.WechatSubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    private final WechatSubscriptionService subscriptions;

    public NotificationController(NotificationService service, WechatSubscriptionService subscriptions) {
        this.service = service;
        this.subscriptions = subscriptions;
    }

    @GetMapping("/subscription-config")
    ApiResponse<WechatSubscriptionService.SubscriptionConfig> subscriptionConfig(CurrentUser actor, HttpServletRequest request) {
        return ApiResponse.success(subscriptions.config(actor), requestId(request));
    }

    @PostMapping("/subscriptions")
    ApiResponse<WechatSubscriptionService.PermissionState> subscribe(CurrentUser actor, @RequestBody SubscriptionRequest body,
                                   HttpServletRequest request) {
        return ApiResponse.success(subscriptions.record(actor, body.decisions(), body.acceptedTemplateIds()), requestId(request));
    }

    @GetMapping("/{id}/source-date")
    ApiResponse<String> sourceDate(CurrentUser actor,@PathVariable UUID id,HttpServletRequest request){return ApiResponse.success(service.sourceDate(actor,id),requestId(request));}
    @GetMapping
    ApiResponse<NotificationService.Inbox> inbox(CurrentUser actor,
            @RequestParam(defaultValue = "100") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="false") boolean unreadOnly, HttpServletRequest request) {
        return ApiResponse.success(service.inbox(actor, limit,offset,unreadOnly), requestId(request));
    }

    @PostMapping("/{id}/read")
    ApiResponse<NotificationService.Item> read(CurrentUser actor, @PathVariable UUID id,
            @RequestBody(required = false) ReadRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.markRead(actor, id, body != null && body.acknowledge()), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }

    public record ReadRequest(boolean acknowledge) {}
    public record SubscriptionRequest(Map<String,String> decisions, List<String> acceptedTemplateIds) {}
}
