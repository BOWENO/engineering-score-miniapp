package com.acme.performance.task.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.incident.service.IncidentService;
import com.acme.performance.performance.service.PerformanceCaseService;
import com.acme.performance.organization.model.BusinessRoles;
import com.acme.performance.performance.service.PerformanceAnalyticsService;
import com.acme.performance.schedule.service.ScheduleService;
import com.acme.performance.organization.service.DataScopeService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class WorkBenchService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");private final JdbcClient jdbc;private final PerformanceAnalyticsService analytics;private final ScheduleService schedules;private final DataScopeService dataScope;private final IncidentService incidents;private final PerformanceCaseService cases;
    public WorkBenchService(JdbcClient jdbc,PerformanceAnalyticsService analytics,ScheduleService schedules,DataScopeService dataScope,IncidentService incidents,PerformanceCaseService cases){this.incidents=incidents;this.cases=cases;this.jdbc=jdbc;this.analytics=analytics;this.schedules=schedules;this.dataScope=dataScope;}
    @Transactional(readOnly=true) public WorkBenchView get(CurrentUser actor){
        boolean scored=actor.roles().stream().anyMatch(BusinessRoles.SCORED::contains);PerformanceAnalyticsService.PersonalView score=scored?analytics.mine(actor,null):null;
        LocalDate today=LocalDate.now(ZONE);List<ScheduleService.AssignmentView> current=schedules.list(actor,today.minusDays(1),today.plusDays(1),actor.userId(),"PUBLISHED").stream().filter(s->!s.shiftEndsAt().isBefore(OffsetDateTime.now(ZONE).minusHours(12))&&!s.shiftStartsAt().isAfter(OffsetDateTime.now(ZONE).plusHours(24))).toList();
        long unread=scalar("SELECT COUNT(*) FROM notification WHERE user_id=:id AND read_at IS NULL",actor.userId());long unack=0;long ownIncidents=scalar("SELECT COUNT(*) FROM incident_statement WHERE responsible_user_id=:id AND status IN ('PENDING','RETURNED','OVERDUE')",actor.userId());long returnedCases=scalar("SELECT COUNT(*) FROM performance_case WHERE target_user_id=:id AND status='RETURNED'",actor.userId());
        long ownCorrective=scalar("SELECT COUNT(*) FROM incident_corrective_action WHERE owner_id=:id AND status IN ('PENDING','IN_PROGRESS','RETURNED')",actor.userId());
        long pendingPerformance=cases.pending(actor).size();
        boolean reviewer=actor.roles().contains(BusinessRoles.SUPERVISOR)||actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER);
        long pendingAppeals=reviewer?cases.pendingAppeals(actor).size():0;
        var queue=incidents.reviewQueue(actor);
        long pendingIncidents=queue.stream().flatMap(item->item.statements().stream()).filter(item->"SUBMITTED".equals(item.status())).count();
        long overdueIncidents=queue.stream().flatMap(item->item.statements().stream()).filter(item->"OVERDUE".equals(item.status())).count();
        long investigationPending=countVisibleIncidents(actor,"INVESTIGATING");long acceptancePending=countVisibleIncidents(actor,"PENDING_ACCEPTANCE");long correctiveOverdue=countVisibleOverdueActions(actor);
        List<HotEquipment> hot=(actor.roles().contains(BusinessRoles.SUPERVISOR)||actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER))?jdbc.sql("""
                SELECT e.id,e.code,e.name,COUNT(DISTINCT i.id) incidents FROM equipment_incident i JOIN incident_equipment ie ON ie.incident_id=i.id JOIN equipment e ON e.id=ie.equipment_id WHERE i.status IN ('ARCHIVED','VOID') AND e.is_review_data=FALSE AND i.occurred_at>=CURRENT_TIMESTAMP-INTERVAL '30 days' GROUP BY e.id,e.code,e.name ORDER BY incidents DESC,e.code LIMIT 5
                """).query((rs,n)->new HotEquipment(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getLong("incidents"))).list():List.of();
        String primaryRole=actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER)?BusinessRoles.DEPARTMENT_MANAGER:actor.roles().contains(BusinessRoles.SUPERVISOR)?BusinessRoles.SUPERVISOR:actor.roles().contains(BusinessRoles.ASSISTANT_ENGINEER)?BusinessRoles.ASSISTANT_ENGINEER:BusinessRoles.TECHNICIAN;
        return new WorkBenchView(primaryRole,actor.administrator(),score,new PendingCounts(unread,unack,ownIncidents,returnedCases,pendingPerformance,pendingAppeals,pendingIncidents,overdueIncidents,ownCorrective,investigationPending,acceptancePending,correctiveOverdue),current,hot);
    }
    private long scalar(String sql,UUID id){return jdbc.sql(sql).param("id",id).query(Long.class).single();}
    private long countVisibleIncidents(CurrentUser actor,String status){return jdbc.sql("SELECT i.id,i.team_id FROM equipment_incident i JOIN app_user u ON u.id=i.created_by WHERE i.status=:status AND u.is_review_account=(SELECT is_review_account FROM app_user WHERE id=:actor)").param("status",status).param("actor",actor.userId()).query((rs,n)->new OrgRow(rs.getObject("id",UUID.class),rs.getObject("team_id",UUID.class))).list().stream().filter(row->dataScope.canAccessOrg(actor,row.orgId())).count();}
    private long countVisibleOverdueActions(CurrentUser actor){return jdbc.sql("SELECT a.id,i.team_id FROM incident_corrective_action a JOIN equipment_incident i ON i.id=a.incident_id JOIN app_user u ON u.id=i.created_by WHERE a.due_at<CURRENT_TIMESTAMP AND a.status NOT IN ('ACCEPTED','CANCELLED') AND i.status NOT IN ('ARCHIVED','VOID') AND u.is_review_account=(SELECT is_review_account FROM app_user WHERE id=:actor)").param("actor",actor.userId()).query((rs,n)->new OrgRow(rs.getObject("id",UUID.class),rs.getObject("team_id",UUID.class))).list().stream().filter(row->dataScope.canAccessOrg(actor,row.orgId())).count();}
    private record OrgRow(UUID id,UUID orgId){}
    public record PendingCounts(long unreadMessages,long unacknowledgedSchedules,long ownIncidentStatements,long returnedCases,long performanceReviews,long appeals,long incidentReviews,long overdueIncidents,long ownCorrectiveActions,long investigations,long pendingAcceptances,long overdueCorrectiveActions){}
    public record HotEquipment(UUID id,String code,String name,long incidents){}
    public record WorkBenchView(String primaryRole,boolean administrator,PerformanceAnalyticsService.PersonalView score,PendingCounts pending,List<ScheduleService.AssignmentView> currentSchedules,List<HotEquipment> hotEquipment){}
}
