package com.acme.performance.incident.service;

import com.acme.performance.notification.service.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CorrectiveActionReminderScheduler {
    private final JdbcClient jdbc;
    private final NotificationService notifications;
    public CorrectiveActionReminderScheduler(JdbcClient jdbc,NotificationService notifications){this.jdbc=jdbc;this.notifications=notifications;}

    @Scheduled(fixedDelayString="${performance.corrective-action-reminder-delay-ms:300000}")
    @Transactional
    public void remind(){
        var rows=jdbc.sql("""
                SELECT a.id,a.incident_id,a.owner_id,i.incident_no,a.content,a.due_at
                FROM incident_corrective_action a JOIN equipment_incident i ON i.id=a.incident_id
                WHERE a.status IN ('PENDING','IN_PROGRESS','RETURNED') AND a.due_at<CURRENT_TIMESTAMP
                  AND (a.reminder_sent_at IS NULL OR a.reminder_sent_at<CURRENT_TIMESTAMP-INTERVAL '24 hours')
                ORDER BY a.due_at FOR UPDATE OF a SKIP LOCKED LIMIT 100
                """).query((rs,n)->new Row(rs.getObject("id",UUID.class),rs.getObject("incident_id",UUID.class),
                rs.getObject("owner_id",UUID.class),rs.getString("incident_no"),rs.getString("content"))).list();
        for(Row row:rows){notifications.create(row.ownerId(),"CORRECTIVE_ACTION_OVERDUE","异常整改已超期",row.incidentNo()+"："+abbreviate(row.content()),"EQUIPMENT_INCIDENT",row.incidentId(),false);jdbc.sql("UPDATE incident_corrective_action SET reminder_sent_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",row.id()).update();}
    }
    private String abbreviate(String value){return value.length()<=80?value:value.substring(0,80)+"…";}
    private record Row(UUID id,UUID incidentId,UUID ownerId,String incidentNo,String content){}
}
