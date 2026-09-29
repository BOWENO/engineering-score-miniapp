package com.acme.performance.admin.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminGuardTest {
    private final AdminGuard guard = new AdminGuard();

    @Test
    void separatesSystemPublishingAndBusinessApprovalRoles() {
        CurrentUser admin = administrator();
        CurrentUser manager = user("DEPARTMENT_MANAGER");
        assertThatCode(() -> guard.requireSystemAdmin(admin)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireDepartmentManager(manager)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireDepartmentManager(admin)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.requireSystemAdmin(manager)).isInstanceOf(ApiException.class);
    }

    private CurrentUser user(String role) {
        return new CurrentUser(UUID.randomUUID(), "TEST", "测试", UUID.randomUUID(), Set.of(role), false);
    }

    private CurrentUser administrator() {
        return new CurrentUser(UUID.randomUUID(), "TEST", "测试", UUID.randomUUID(), Set.of("SUPERVISOR"), false, true, false);
    }
}
