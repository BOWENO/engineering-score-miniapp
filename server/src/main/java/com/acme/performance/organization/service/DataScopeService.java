package com.acme.performance.organization.service;

import com.acme.performance.auth.model.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DataScopeService {
    private final JdbcClient jdbc;

    public DataScopeService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean canAccessUser(CurrentUser actor, UUID targetUserId) {
        if (actor.userId().equals(targetUserId)) return true;
        if (actor.administrator()) return true;
        if (actor.roles().stream().noneMatch(role -> role.equals("SUPERVISOR") || role.equals("DEPARTMENT_MANAGER")
                || role.equals("ASSISTANT_ENGINEER"))) return false;
        Long matches = jdbc.sql("""
                WITH RECURSIVE allowed_org AS (
                    SELECT scope_id AS id FROM role_binding WHERE user_id=:actorId AND scope_id IS NOT NULL
                    UNION ALL
                    SELECT child.id FROM org_unit child JOIN allowed_org parent ON child.parent_id=parent.id
                )
                SELECT COUNT(*) FROM app_user target JOIN allowed_org allowed ON target.org_unit_id=allowed.id
                WHERE target.id=:targetUserId
                """).param("actorId", actor.userId()).param("targetUserId", targetUserId).query(Long.class).single();
        return matches > 0;
    }

    public boolean canAccessOrg(CurrentUser actor, UUID targetOrgId) {
        if (targetOrgId == null) return false;
        if (actor.administrator()) return true;
        if (actor.roles().stream().noneMatch(role -> role.equals("SUPERVISOR") || role.equals("DEPARTMENT_MANAGER")
                || role.equals("ASSISTANT_ENGINEER"))) return false;
        Long matches = jdbc.sql("""
                WITH RECURSIVE allowed_org AS (
                    SELECT scope_id AS id FROM role_binding WHERE user_id=:actorId AND scope_id IS NOT NULL
                    UNION
                    SELECT :actorOrgId WHERE :actorOrgId IS NOT NULL
                    UNION ALL
                    SELECT child.id FROM org_unit child JOIN allowed_org parent ON child.parent_id=parent.id
                )
                SELECT COUNT(*) FROM allowed_org WHERE id=:targetOrgId
                """).param("actorId", actor.userId()).param("actorOrgId", actor.orgUnitId())
                .param("targetOrgId", targetOrgId).query(Long.class).single();
        return matches > 0;
    }
}
