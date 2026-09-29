package com.acme.performance.incident.service;

import com.acme.performance.notification.service.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class IncidentDeadlineScheduler {
    private final JdbcClient jdbc;private final NotificationService notifications;
    public IncidentDeadlineScheduler(JdbcClient jdbc,NotificationService notifications){this.jdbc=jdbc;this.notifications=notifications;}
    @Scheduled(fixedDelayString="${performance.incident-deadline-delay-ms:300000}")
    @Transactional public void markOverdue(){
        List<Row> rows=jdbc.sql("""
                SELECT s.id,s.incident_id,s.responsible_user_id,u.display_name,i.incident_no
                FROM incident_statement s JOIN app_user u ON u.id=s.responsible_user_id
                JOIN equipment_incident i ON i.id=s.incident_id
                WHERE (s.status='PENDING' AND s.due_at<CURRENT_TIMESTAMP)
                   OR (s.status='RETURNED' AND s.resubmit_due_at<CURRENT_TIMESTAMP)
                FOR UPDATE OF s SKIP LOCKED
                """).query((rs,n)->new Row(rs.getObject("id",UUID.class),rs.getObject("incident_id",UUID.class),rs.getObject("responsible_user_id",UUID.class),rs.getString("display_name"),rs.getString("incident_no"))).list();
        for(Row row:rows){jdbc.sql("UPDATE incident_statement SET status='OVERDUE',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",row.statementId()).update();notifications.create(row.userId(),"INCIDENT_OVERDUE","异常说明已逾期",row.no()+" 仍需尽快提交。","EQUIPMENT_INCIDENT",row.incidentId(),false);jdbc.sql("SELECT DISTINCT rb.user_id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE'").query(UUID.class).list().forEach(supervisor->notifications.create(supervisor,"INCIDENT_OVERDUE","异常说明逾期",row.name()+" 未按时提交 "+row.no(),"EQUIPMENT_INCIDENT",row.incidentId(),false));}
    }
    private record Row(UUID statementId,UUID incidentId,UUID userId,String name,String no){}
}
