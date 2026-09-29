package com.acme.performance.approval.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.organization.service.DataScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ApprovalServiceTest {
    @Test
    void administratorWithoutBusinessRoleCannotPerformBusinessApproval() {
        ApprovalService service = new ApprovalService(mock(JdbcClient.class), mock(DataScopeService.class), new ObjectMapper());
        CurrentUser admin = new CurrentUser(UUID.randomUUID(), "A001", "平台管理员", UUID.randomUUID(), Set.of(), false, true, false);
        assertThatThrownBy(() -> service.pending(admin)).isInstanceOf(ApiException.class)
                .hasMessageContaining("不能执行业务审核");
    }
}
