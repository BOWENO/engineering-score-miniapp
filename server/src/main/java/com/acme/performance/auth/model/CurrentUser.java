package com.acme.performance.auth.model;

import java.util.Set;
import java.util.UUID;

public record CurrentUser(UUID userId, String employeeNo, String displayName, UUID orgUnitId,
                          Set<String> roles, boolean wechatBound, boolean administrator,
                          boolean passwordChangeRequired) {
    public CurrentUser(UUID userId, String employeeNo, String displayName, UUID orgUnitId,
                       Set<String> roles, boolean wechatBound) {
        this(userId, employeeNo, displayName, orgUnitId, roles, wechatBound, false, false);
    }
}
