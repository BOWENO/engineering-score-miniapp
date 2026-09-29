package com.acme.performance.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class WechatNotificationSender {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;
    private final RestClient restClient;
    private final String appId;
    private final String appSecret;
    private final String templateId;
    private final String titleKey;
    private final String contentKey;
    private final String timeKey;
    private final String miniProgramState;
    private volatile AccessToken cachedToken;

    public WechatNotificationSender(JdbcClient jdbc, RestClient restClient,
                                    @Value("${wechat.app-id:}") String appId,
                                    @Value("${wechat.app-secret:}") String appSecret,
                                    @Value("${wechat.subscribe-template-id:}") String templateId,
                                    @Value("${wechat.subscribe-title-key:thing1}") String titleKey,
                                    @Value("${wechat.subscribe-content-key:thing2}") String contentKey,
                                    @Value("${wechat.subscribe-time-key:time3}") String timeKey,
                                    @Value("${wechat.miniprogram-state:formal}") String miniProgramState) {
        this.jdbc = jdbc;
        this.restClient = restClient;
        this.appId = appId;
        this.appSecret = appSecret;
        this.templateId = templateId;
        this.titleKey = titleKey;
        this.contentKey = contentKey;
        this.timeKey = timeKey;
        this.miniProgramState = miniProgramState;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(NotificationCreatedEvent event) {
        Delivery row = jdbc.sql("""
                SELECT n.id,n.user_id,u.openid,n.title,n.content FROM notification n
                JOIN app_user u ON u.id=n.user_id WHERE n.id=:id
                """).param("id", event.notificationId()).query((rs, n) -> new Delivery(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("openid"),
                rs.getString("title"), rs.getString("content"))).optional().orElse(null);
        if (row == null) return;
        if (templateId.isBlank() || appId.isBlank() || appSecret.isBlank()) {
            status(row.id(), "CONFIG_MISSING");
            return;
        }
        if (row.openid() == null || row.openid().isBlank()) {
            status(row.id(), "NOT_BOUND");
            return;
        }
        if (!hasCredit(row.userId())) {
            status(row.id(), "NOT_SUBSCRIBED");
            return;
        }
        try {
            Map<String, Object> response = send(row, token());
            int code = number(response.get("errcode"));
            if (code == 40001 || code == 42001) {
                cachedToken = null;
                response = send(row, token());
                code = number(response.get("errcode"));
            }
            if (code == 0) {
                jdbc.sql("""
                        UPDATE wechat_subscription_credit SET credits=GREATEST(credits-1,0),
                          permission_status=CASE WHEN credits<=1 THEN 'EXHAUSTED' ELSE 'ACCEPTED' END,
                          updated_at=CURRENT_TIMESTAMP
                        WHERE user_id=:userId AND template_id=:templateId
                        """).param("userId", row.userId()).param("templateId", templateId).update();
                status(row.id(), "SENT");
            } else {
                if (code == 43101) jdbc.sql("UPDATE wechat_subscription_credit SET credits=0,permission_status='BANNED',last_error_code='43101',updated_at=CURRENT_TIMESTAMP WHERE user_id=:userId AND template_id=:templateId")
                        .param("userId", row.userId()).param("templateId", templateId).update();
                status(row.id(), "FAILED");
            }
        } catch (RuntimeException ex) {
            status(row.id(), "FAILED");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(Delivery row, String accessToken) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (!titleKey.isBlank()) data.put(titleKey, Map.of("value", truncate(row.title(), 20)));
        if (!contentKey.isBlank()) data.put(contentKey, Map.of("value", truncate(row.content(), 20)));
        if (!timeKey.isBlank()) data.put(timeKey, Map.of("value", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(OffsetDateTime.now(ZONE))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", row.openid());
        body.put("template_id", templateId);
        body.put("page", "pages/messages/index");
        body.put("miniprogram_state", miniProgramState);
        body.put("lang", "zh_CN");
        body.put("data", data);
        Map<String, Object> response = restClient.post()
                .uri("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token={token}", accessToken)
                .body(body).retrieve().body(Map.class);
        return response == null ? Map.of("errcode", -1) : response;
    }

    @SuppressWarnings("unchecked")
    private synchronized String token() {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        if (cachedToken != null && cachedToken.expiresAt().isAfter(now.plusMinutes(2))) return cachedToken.value();
        Map<String, Object> response = restClient.get().uri(builder -> builder.scheme("https").host("api.weixin.qq.com")
                .path("/cgi-bin/token").queryParam("grant_type", "client_credential")
                .queryParam("appid", appId).queryParam("secret", appSecret).build()).retrieve().body(Map.class);
        if (response == null || response.get("access_token") == null) throw new IllegalStateException("微信access_token获取失败");
        int expiresIn = response.get("expires_in") instanceof Number number ? number.intValue() : 7200;
        cachedToken = new AccessToken(String.valueOf(response.get("access_token")), now.plusSeconds(expiresIn));
        return cachedToken.value();
    }

    private boolean hasCredit(UUID userId) {
        return jdbc.sql("SELECT COUNT(*) FROM wechat_subscription_credit WHERE user_id=:userId AND template_id=:templateId AND credits>0")
                .param("userId", userId).param("templateId", templateId).query(Long.class).single() > 0;
    }

    private void status(UUID id, String value) {
        jdbc.sql("UPDATE notification SET wechat_delivery_status=:status WHERE id=:id")
                .param("status", value).param("id", id).update();
    }

    private int number(Object value) { return value instanceof Number number ? number.intValue() : -1; }
    private String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }

    private record AccessToken(String value, OffsetDateTime expiresAt) {}
    private record Delivery(UUID id, UUID userId, String openid, String title, String content) {}
}
