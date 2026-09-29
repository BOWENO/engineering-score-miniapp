package com.acme.performance.task.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.task.model.DashboardView;
import com.acme.performance.task.service.DashboardService;
import com.acme.performance.task.service.TaskCompletionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskControllerTest {
    private final DashboardService dashboardService = mock(DashboardService.class);
    private final TaskCompletionService completionService = mock(TaskCompletionService.class);
    private final TaskController controller = new TaskController(dashboardService, completionService);

    @Test
    void returnsDashboardForCurrentUser() {
        UUID userId = UUID.randomUUID();
        CurrentUser user = new CurrentUser(userId, "T001", "测试技术员", UUID.randomUUID(), Set.of("TECHNICIAN"), false);
        DashboardView dashboard = new DashboardView("2026-08", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, 1, 1, List.of());
        when(dashboardService.get(userId)).thenReturn(dashboard);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "request-1");

        var response = controller.dashboard(user, request);

        assertThat(response.data()).isEqualTo(dashboard);
        assertThat(response.requestId()).isEqualTo("request-1");
    }

    @Test
    void delegatesCompletionWithConcurrencyAndIdempotencyValues() {
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        CurrentUser user = new CurrentUser(userId, "T001", "测试技术员", UUID.randomUUID(), Set.of("TECHNICIAN"), false);
        var result = new TaskCompletionService.CompletionResult(UUID.randomUUID(), false);
        when(completionService.complete(userId, taskId, "key-1", 3, "42", "完成"))
                .thenReturn(result);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "request-2");

        var response = controller.complete(user, taskId, "key-1",
                new TaskController.CompletionRequest(3, "42", "完成"), request);

        assertThat(response.data()).isEqualTo(result);
        verify(completionService).complete(userId, taskId, "key-1", 3, "42", "完成");
    }
}
