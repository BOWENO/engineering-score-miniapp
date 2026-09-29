package com.acme.performance.performance.service;

import com.acme.performance.notification.service.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PerformanceDeadlineScheduler {
    private final JdbcClient jdbc; private final NotificationService notifications;
    public PerformanceDeadlineScheduler(JdbcClient jdbc,NotificationService notifications){this.jdbc=jdbc;this.notifications=notifications;}
    @Scheduled(fixedDelayString="${performance.deadline-delay-ms:300000}")
    @Transactional public void closeExpiredAppeals(){
        List<Row> expired=jdbc.sql("SELECT id,applicant_id FROM performance_appeal WHERE status='PENDING' AND due_at<CURRENT_TIMESTAMP FOR UPDATE SKIP LOCKED")
                .query((rs,n)->new Row(rs.getObject("id",UUID.class),rs.getObject("applicant_id",UUID.class))).list();
        for(Row row:expired){jdbc.sql("UPDATE performance_appeal SET status='REJECTED',decided_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id AND status='PENDING'").param("id",row.id()).update();notifications.create(row.userId(),"APPEAL_RESULT","扣分申诉已超时驳回","主管未在规定时间内完成表决，系统按规则驳回。","PERFORMANCE_APPEAL",row.id(),false);}
    }
    private record Row(UUID id,UUID userId){}
}
