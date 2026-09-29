package com.acme.performance.admin.service;

import com.acme.performance.auth.model.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AuditLogService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AdminGuard guard;

    public AuditLogService(JdbcClient jdbc, ObjectMapper mapper, AdminGuard guard) {
        this.jdbc = jdbc; this.mapper = mapper; this.guard = guard;
    }

    public void record(UUID actorId, String action, String targetType, UUID targetId, String afterJson, String requestId) {
        jdbc.sql("""
                INSERT INTO operation_log(id,actor_id,action,target_type,target_id,after_data,request_id)
                VALUES (:id,:actorId,:action,:targetType,:targetId,CAST(:afterJson AS jsonb),:requestId)
                """).param("id", UUID.randomUUID()).param("actorId", actorId).param("action", action)
                .param("targetType", targetType).param("targetId", targetId, java.sql.Types.OTHER).param("afterJson", afterJson)
                .param("requestId", requestId).update();
    }

    public List<AuditItem> latest(CurrentUser user, int limit) {
        guard.requireSystemAdmin(user);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.sql("""
                SELECT l.id,l.actor_id,u.display_name,l.action,l.target_type,l.target_id,l.after_data::text,l.request_id,l.created_at
                FROM operation_log l LEFT JOIN app_user u ON u.id=l.actor_id ORDER BY l.created_at DESC LIMIT :limit
                """).param("limit", safeLimit).query((rs, n) -> new AuditItem(rs.getObject("id", UUID.class),
                        rs.getObject("actor_id", UUID.class), rs.getString("display_name"), rs.getString("action"),
                        rs.getString("target_type"), rs.getObject("target_id", UUID.class), parse(rs.getString("after_data")),
                        rs.getString("request_id"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private JsonNode parse(String value) {
        try { return value == null ? mapper.nullNode() : mapper.readTree(value); }
        catch (Exception ex) { return mapper.createObjectNode().put("unreadable", true); }
    }

    public record AuditItem(UUID id, UUID actorId, String actorName, String action, String targetType,
                            UUID targetId, JsonNode after, String requestId, OffsetDateTime createdAt) {}
}
