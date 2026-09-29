package com.acme.performance.equipment.service;

import com.acme.performance.admin.service.AuditLogService;
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

class DeviceExceptionServiceTest {
    private final DeviceExceptionService service = new DeviceExceptionService(
            mock(JdbcClient.class), mock(DataScopeService.class), mock(AuditLogService.class), new ObjectMapper());

    @Test
    void technicianCannotReviewDeviceException() {
        CurrentUser technician = new CurrentUser(UUID.randomUUID(), "T001", "技术员",
                UUID.randomUUID(), Set.of("TECHNICIAN"), false);

        assertThatThrownBy(() -> service.pending(technician))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("主管");
    }

    @Test
    void requiredFieldsAreValidatedBeforePersistence() {
        CurrentUser technician = new CurrentUser(UUID.randomUUID(), "T001", "技术员",
                UUID.randomUUID(), Set.of("TECHNICIAN"), false);

        assertThatThrownBy(() -> service.create(technician, "key-1", UUID.randomUUID(), null,
                "", "处理方法", "根因", null, true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("均为必填项");
    }
}
