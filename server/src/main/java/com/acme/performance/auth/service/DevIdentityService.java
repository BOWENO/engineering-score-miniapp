package com.acme.performance.auth.service;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Profile({"local", "test"})
@Service
public class DevIdentityService {
    private final JdbcClient jdbc;

    public DevIdentityService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID ensureUser(String employeeNo, String displayName, String roleCode) {
        UUID orgId = jdbc.sql("SELECT id FROM org_unit WHERE parent_id IS NULL AND type='DEPARTMENT' ORDER BY created_at LIMIT 1")
                .query(UUID.class).optional().orElseGet(this::createDefaultOrg);
        UUID userId = jdbc.sql("SELECT id FROM app_user WHERE employee_no = :employeeNo")
                .param("employeeNo", employeeNo).query(UUID.class).optional()
                .orElseGet(() -> createUser(employeeNo, displayName, orgId));
        Long count = jdbc.sql("SELECT COUNT(*) FROM role_binding WHERE user_id=:userId AND role_code=:roleCode AND scope_id=:scopeId")
                .param("userId", userId).param("roleCode", roleCode).param("scopeId", orgId).query(Long.class).single();
        if (count == 0L) {
            jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:userId,:roleCode,:scopeId)")
                    .param("id", UUID.randomUUID()).param("userId", userId)
                    .param("roleCode", roleCode).param("scopeId", orgId).update();
        }
        return userId;
    }

    private UUID createDefaultOrg() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO org_unit(id,type,name) VALUES (:id,'DEPARTMENT','测试工程部')").param("id", id).update();
        return id;
    }

    private UUID createUser(String employeeNo, String displayName, UUID orgId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status) VALUES (:id,:employeeNo,:displayName,:orgId,'ACTIVE')")
                .param("id", id).param("employeeNo", employeeNo).param("displayName", displayName)
                .param("orgId", orgId).update();
        return id;
    }
}
