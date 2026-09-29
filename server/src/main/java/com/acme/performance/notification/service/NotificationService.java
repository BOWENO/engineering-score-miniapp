package com.acme.performance.notification.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public NotificationService(JdbcClient jdbc, ApplicationEventPublisher events) { this.jdbc = jdbc; this.events = events; }

    @Transactional(readOnly=true)
    public String sourceDate(CurrentUser actor,UUID id){
        return jdbc.sql("""
            SELECT COALESCE(CASE WHEN n.source_type='SCHEDULE_ASSIGNMENT' THEN
              (SELECT business_date::text FROM schedule_assignment WHERE id=n.source_id)
              WHEN n.source_type='PERFORMANCE_CASE' THEN
              (SELECT (occurred_at AT TIME ZONE 'Asia/Shanghai')::date::text FROM performance_case WHERE id=n.source_id)
              END,'') FROM notification n WHERE n.id=:id AND n.user_id=:user
            """).param("id",id).param("user",actor.userId()).query(String.class).optional().orElse("");
    }
    public UUID create(UUID userId, String type, String title, String content,
                       String sourceType, UUID sourceId, boolean requiresAcknowledgement) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification(id,user_id,type,title,content,source_type,source_id,requires_acknowledgement)
                VALUES (:id,:userId,:type,:title,:content,:sourceType,:sourceId,:requiresAck)
                """).param("id", id).param("userId", userId).param("type", type).param("title", title)
                .param("content", content).param("sourceType", sourceType).param("sourceId", sourceId)
                .param("requiresAck", requiresAcknowledgement).update();
        events.publishEvent(new NotificationCreatedEvent(id));
        return id;
    }

    public boolean createOnce(UUID userId, String type, String title, String content,
                              String sourceType, UUID sourceId, boolean requiresAcknowledgement) {
        long exists = jdbc.sql("""
                SELECT COUNT(*) FROM notification
                WHERE user_id=:userId AND type=:type
                  AND source_type IS NOT DISTINCT FROM :sourceType
                  AND source_id IS NOT DISTINCT FROM :sourceId
                """).param("userId", userId).param("type", type).param("sourceType", sourceType)
                .param("sourceId", sourceId).query(Long.class).single();
        if (exists > 0) return false;
        create(userId, type, title, content, sourceType, sourceId, requiresAcknowledgement);
        return true;
    }

    @Transactional(readOnly = true)
    public Inbox inbox(CurrentUser actor, int limit) {
        return inbox(actor,limit,0,false);
    }
    @Transactional(readOnly=true)
    public Inbox inbox(CurrentUser actor,int limit,int offset,boolean unreadOnly){
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<Item> items = jdbc.sql("""
                SELECT id,type,title,content,source_type,source_id,requires_acknowledgement,
                       read_at,acknowledged_at,wechat_delivery_status,created_at
                FROM notification WHERE user_id=:userId AND (:unreadOnly=FALSE OR read_at IS NULL)
                ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset
                """).param("userId", actor.userId()).param("limit", safeLimit).param("offset",Math.max(0,offset)).param("unreadOnly",unreadOnly)
                .query((rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getString("title"), rs.getString("content"), rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class), rs.getBoolean("requires_acknowledgement"),
                        rs.getObject("read_at", OffsetDateTime.class), rs.getObject("acknowledged_at", OffsetDateTime.class),
                        rs.getString("wechat_delivery_status"), rs.getObject("created_at", OffsetDateTime.class))).list();
        long unread = jdbc.sql("SELECT count(*) FROM notification WHERE user_id=:id AND read_at IS NULL").param("id",actor.userId()).query(Long.class).single();
        long awaitingAck = jdbc.sql("SELECT count(*) FROM notification WHERE user_id=:id AND requires_acknowledgement AND acknowledged_at IS NULL").param("id",actor.userId()).query(Long.class).single();
        long total=jdbc.sql("SELECT count(*) FROM notification WHERE user_id=:id AND (:unreadOnly=FALSE OR read_at IS NULL)").param("id",actor.userId()).param("unreadOnly",unreadOnly).query(Long.class).single();
        return new Inbox(unread, awaitingAck, items,total,Math.max(0,offset)+items.size()<total);
    }

    @Transactional
    public Item markRead(CurrentUser actor, UUID notificationId, boolean acknowledge) {
        int updated = jdbc.sql("""
                UPDATE notification SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP),
                  acknowledged_at=CASE WHEN :ack AND requires_acknowledgement THEN COALESCE(acknowledged_at,CURRENT_TIMESTAMP)
                                       ELSE acknowledged_at END
                WHERE id=:id AND user_id=:userId
                """).param("ack", acknowledge).param("id", notificationId).param("userId", actor.userId()).update();
        if (updated != 1) throw new ApiException("NOTIFICATION_NOT_FOUND", "消息不存在", HttpStatus.NOT_FOUND);
        return jdbc.sql("""
                SELECT id,type,title,content,source_type,source_id,requires_acknowledgement,
                       read_at,acknowledged_at,wechat_delivery_status,created_at
                FROM notification WHERE id=:id
                """).param("id", notificationId).query((rs, n) -> new Item(rs.getObject("id", UUID.class),
                        rs.getString("type"),rs.getString("title"),rs.getString("content"),rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class),rs.getBoolean("requires_acknowledgement"),
                        rs.getObject("read_at", OffsetDateTime.class),rs.getObject("acknowledged_at", OffsetDateTime.class),
                        rs.getString("wechat_delivery_status"),rs.getObject("created_at", OffsetDateTime.class))).single();
    }

    public record Inbox(long unread, long awaitingAcknowledgement, List<Item> items,long total,boolean hasMore) {}
    public record Item(UUID id, String type, String title, String content, String sourceType, UUID sourceId,
                       boolean requiresAcknowledgement, OffsetDateTime readAt, OffsetDateTime acknowledgedAt,
                       String wechatDeliveryStatus, OffsetDateTime createdAt) {}
}
