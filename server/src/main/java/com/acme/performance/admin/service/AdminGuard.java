package com.acme.performance.admin.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminGuard {
    public void requireSystemAdmin(CurrentUser user) {
        if (!user.administrator()) deny();
    }

    public void requireDepartmentManager(CurrentUser user) {
        if (!user.roles().contains("DEPARTMENT_MANAGER")) deny();
    }

    public void requireAdministratorOrManager(CurrentUser user) {
        if (!user.administrator() && !user.roles().contains("DEPARTMENT_MANAGER")) deny();
    }

    private void deny() {
        throw new ApiException("FORBIDDEN", "当前角色无权执行该管理操作", HttpStatus.FORBIDDEN);
    }
}
