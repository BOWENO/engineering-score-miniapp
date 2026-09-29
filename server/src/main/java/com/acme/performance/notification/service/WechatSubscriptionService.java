package com.acme.performance.notification.service;

import com.acme.performance.auth.model.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.time.OffsetDateTime;
import java.util.Set;

@Service
public class WechatSubscriptionService {
    private static final Set<String> ELIGIBLE_ROLES = Set.of("TECHNICIAN", "ASSISTANT_ENGINEER");
    private final JdbcClient jdbc;
    private final String templateId;

    public WechatSubscriptionService(JdbcClient jdbc,
                                     @Value("${wechat.subscribe-template-id:}") String templateId) {
        this.jdbc = jdbc;
        this.templateId = templateId;
    }

    public SubscriptionConfig config(CurrentUser actor) {
        boolean eligible = actor.roles().stream().anyMatch(ELIGIBLE_ROLES::contains);
        PermissionState state = state(actor);
        return new SubscriptionConfig(!templateId.isBlank() && eligible,
                templateId.isBlank() || !eligible ? List.of() : List.of(templateId), eligible,
                state.status(), state.credits(), state.updatedAt());
    }

    @Transactional
    public PermissionState record(CurrentUser actor, Map<String, String> decisions, List<String> acceptedTemplateIds) {
        if (templateId.isBlank() || actor.roles().stream().noneMatch(ELIGIBLE_ROLES::contains)) return state(actor);
        String decision = decisions == null ? null : decisions.get(templateId);
        if (decision == null && acceptedTemplateIds != null && acceptedTemplateIds.contains(templateId)) decision = "accept";
        String status = switch (decision == null ? "" : decision.toLowerCase()) {
            case "accept" -> "ACCEPTED";
            case "reject" -> "REJECTED";
            case "ban" -> "BANNED";
            default -> "NOT_REQUESTED";
        };
        if ("NOT_REQUESTED".equals(status)) return state(actor);
        int increment = "ACCEPTED".equals(status) ? 1 : 0;
        jdbc.sql("""
                INSERT INTO wechat_subscription_credit(user_id,template_id,credits,permission_status,last_requested_at,last_response_at)
                VALUES (:userId,:templateId,:increment,:status,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON CONFLICT (user_id,template_id) DO UPDATE SET
                  credits=CASE WHEN :increment=1 THEN LEAST(wechat_subscription_credit.credits+1,20)
                               WHEN :status='BANNED' THEN 0 ELSE wechat_subscription_credit.credits END,
                  permission_status=:status,last_requested_at=CURRENT_TIMESTAMP,last_response_at=CURRENT_TIMESTAMP,
                  last_error_code=NULL,updated_at=CURRENT_TIMESTAMP
                """).param("userId", actor.userId()).param("templateId", templateId)
                .param("increment", increment).param("status", status).update();
        return state(actor);
    }

    private PermissionState state(CurrentUser actor) {
        if (templateId.isBlank()) return new PermissionState("NOT_CONFIGURED", 0, null);
        return jdbc.sql("SELECT permission_status,credits,updated_at FROM wechat_subscription_credit WHERE user_id=:userId AND template_id=:templateId")
                .param("userId", actor.userId()).param("templateId", templateId)
                .query((rs, n) -> new PermissionState(rs.getString("permission_status"), rs.getInt("credits"),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .optional().orElse(new PermissionState("NOT_REQUESTED", 0, null));
    }

    public record SubscriptionConfig(boolean enabled, List<String> templateIds, boolean eligible,
                                     String permissionStatus, int credits, OffsetDateTime updatedAt) {}
    public record PermissionState(String status, int credits, OffsetDateTime updatedAt) {}
}
