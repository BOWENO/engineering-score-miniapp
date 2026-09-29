package com.acme.performance.settlement.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.List;
import java.util.UUID;

/** Settlement access is based on the binding for the relevant role, never unrelated roles. */
final class SettlementAccess {
    private final JdbcClient jdbc;
    SettlementAccess(JdbcClient jdbc) { this.jdbc = jdbc; }

    boolean canAccess(CurrentUser actor, UUID orgId) {
        return actor.roles().stream().filter(role -> role.equals("SUPERVISOR") || role.equals("DEPARTMENT_MANAGER"))
                .anyMatch(role -> allowed(actor.userId(), orgId, role));
    }

    private boolean allowed(UUID userId, UUID orgId, String role) {
        return jdbc.sql("""
                WITH RECURSIVE scope AS (
                  SELECT o.id FROM role_binding rb JOIN org_unit o ON o.id=rb.scope_id
                  JOIN app_user u ON u.id=rb.user_id
                  WHERE rb.user_id=:userId AND rb.role_code=:role AND u.status='ACTIVE'
                    AND NOT u.is_review_account AND NOT o.is_review_data
                  UNION
                  SELECT o.id FROM org_unit o JOIN scope s ON o.parent_id=s.id WHERE NOT o.is_review_data
                ) SELECT COUNT(*) FROM scope WHERE id=:orgId
                """).param("userId", userId).param("role", role).param("orgId", orgId)
                .query(Long.class).single() > 0;
    }

    void require(CurrentUser actor, UUID orgId) {
        if (!canAccess(actor, orgId)) throw new ApiException("FORBIDDEN", "无权访问该组织的月度封存", HttpStatus.FORBIDDEN);
    }

    void requireWrite(CurrentUser actor, UUID orgId) {
        if (!actor.roles().contains("SUPERVISOR") || !allowed(actor.userId(), orgId, "SUPERVISOR"))
            throw new ApiException("FORBIDDEN", "无权操作该组织的月度封存", HttpStatus.FORBIDDEN);
    }

    List<UUID> supervisors(UUID orgId) {
        return jdbc.sql("""
                SELECT DISTINCT u.id FROM app_user u JOIN role_binding rb ON rb.user_id=u.id
                WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE' AND NOT u.is_review_account
                """).query(UUID.class).list().stream().filter(id -> allowed(id, orgId, "SUPERVISOR")).toList();
    }

    void snapshotRun(UUID runId, UUID orgId) {
        for (UUID id : supervisors(orgId)) jdbc.sql("INSERT INTO settlement_approver(run_id,user_id) VALUES (:run,:user)")
                .param("run", runId).param("user", id).update();
    }

    List<UUID> voters(UUID runId) {
        return jdbc.sql("SELECT user_id FROM settlement_approver WHERE run_id=:id").param("id", runId).query(UUID.class).list();
    }

    void requireVoter(CurrentUser actor, UUID runId) {
        if (!voters(runId).contains(actor.userId())) throw new ApiException("FORBIDDEN", "您不在本批次的审批人名单中", HttpStatus.FORBIDDEN);
    }
}
