package com.acme.performance.incident.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.organization.service.DataScopeService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class IncidentReportService {
    private final JdbcClient jdbc;
    private final DataScopeService dataScope;
    public IncidentReportService(JdbcClient jdbc,DataScopeService dataScope){this.jdbc=jdbc;this.dataScope=dataScope;}

    @Transactional(readOnly=true)
    public MonthlyReport monthly(CurrentUser actor,LocalDate from,LocalDate to){
        LocalDate safeFrom=from==null?LocalDate.now().withDayOfMonth(1):from;
        LocalDate safeTo=to==null?safeFrom.plusMonths(1).minusDays(1):to;
        List<IncidentRow> visible=jdbc.sql("""
                SELECT i.id,i.incident_no,i.team_id,t.name team_name,i.category_code,i.severity,i.status,
                  i.downtime_minutes,i.occurred_at,i.archived_at,inv.root_cause_category
                FROM equipment_incident i JOIN org_unit t ON t.id=i.team_id
                LEFT JOIN incident_investigation inv ON inv.incident_id=i.id
                JOIN app_user creator ON creator.id=i.created_by
                WHERE i.business_date BETWEEN :from AND :to AND creator.is_review_account=:reviewAccount
                ORDER BY i.occurred_at DESC,i.id DESC
                """).param("from",safeFrom).param("to",safeTo)
                .param("reviewAccount","wxreview".equalsIgnoreCase(actor.employeeNo()))
                .query((rs,n)->new IncidentRow(rs.getObject("id",UUID.class),rs.getString("incident_no"),
                        rs.getObject("team_id",UUID.class),rs.getString("team_name"),rs.getString("category_code"),
                        rs.getString("severity"),rs.getString("status"),rs.getInt("downtime_minutes"),
                        rs.getObject("occurred_at",OffsetDateTime.class),rs.getObject("archived_at",OffsetDateTime.class),
                        rs.getString("root_cause_category"))).list().stream().filter(i->dataScope.canAccessOrg(actor,i.teamId())).toList();
        int totalDowntime=visible.stream().mapToInt(IncidentRow::downtimeMinutes).sum();
        long archived=visible.stream().filter(i->"ARCHIVED".equals(i.status())).count();
        long overdueActions=visible.isEmpty()?0:jdbc.sql("""
                SELECT COUNT(*) FROM incident_corrective_action a
                WHERE a.due_at<CURRENT_TIMESTAMP AND a.status NOT IN ('ACCEPTED','CANCELLED') AND a.incident_id IN (:incidentIds)
                """).param("incidentIds",visible.stream().map(IncidentRow::id).toList()).query(Long.class).single();
        List<DimensionCount> categories=group(visible.stream().map(IncidentRow::categoryCode).toList());
        List<DimensionCount> rootCauses=group(visible.stream().map(i->i.rootCauseCategory()==null?"待分析":i.rootCauseCategory()).toList());
        List<DimensionCount> teams=group(visible.stream().map(IncidentRow::teamName).toList());
        double avgCloseHours=visible.stream().filter(i->i.archivedAt()!=null).mapToLong(i->java.time.Duration.between(i.occurredAt(),i.archivedAt()).toMinutes()).average().orElse(0)/60d;
        return new MonthlyReport(safeFrom,safeTo,visible.size(),archived,visible.size()-archived,totalDowntime,
                BigDecimal.valueOf(avgCloseHours).setScale(2,java.math.RoundingMode.HALF_UP),overdueActions,categories,rootCauses,teams,visible);
    }

    @Transactional(readOnly=true)
    public List<RepeatCandidate> repeatCandidates(CurrentUser actor,int days){
        int safeDays=Math.max(7,Math.min(days,365));
        return jdbc.sql("""
                SELECT newer.id newer_id,newer.incident_no newer_no,older.id older_id,older.incident_no older_no,
                  e.id equipment_id,e.code,e.name,newer.category_code,newer.team_id,
                  EXTRACT(DAY FROM newer.occurred_at-older.occurred_at)::int days_apart
                FROM equipment_incident newer JOIN incident_equipment ne ON ne.incident_id=newer.id
                JOIN equipment e ON e.id=ne.equipment_id JOIN incident_equipment oe ON oe.equipment_id=e.id
                JOIN equipment_incident older ON older.id=oe.incident_id AND older.occurred_at<newer.occurred_at
                WHERE newer.category_code=older.category_code
                  AND newer.occurred_at-older.occurred_at <= (:days || ' days')::interval
                  AND NOT EXISTS (SELECT 1 FROM incident_relation r WHERE r.incident_id=newer.id AND r.related_incident_id=older.id)
                ORDER BY newer.occurred_at DESC LIMIT 200
                """).param("days",String.valueOf(safeDays)).query((rs,n)->new RepeatCandidate(
                rs.getObject("newer_id",UUID.class),rs.getString("newer_no"),rs.getObject("older_id",UUID.class),
                rs.getString("older_no"),rs.getObject("equipment_id",UUID.class),rs.getString("code"),rs.getString("name"),
                rs.getString("category_code"),rs.getObject("team_id",UUID.class),rs.getInt("days_apart")))
                .list().stream().filter(item->dataScope.canAccessOrg(actor,item.teamId())).toList();
    }

    private List<DimensionCount> group(List<String> values){return values.stream().collect(java.util.stream.Collectors.groupingBy(v->v,java.util.stream.Collectors.counting())).entrySet().stream().map(e->new DimensionCount(e.getKey(),e.getValue())).sorted(java.util.Comparator.comparing(DimensionCount::count).reversed()).toList();}
    public record DimensionCount(String name,long count){}
    public record IncidentRow(UUID id,String incidentNo,UUID teamId,String teamName,String categoryCode,String severity,String status,int downtimeMinutes,OffsetDateTime occurredAt,OffsetDateTime archivedAt,String rootCauseCategory){}
    public record MonthlyReport(LocalDate from,LocalDate to,long total,long archived,long open,int downtimeMinutes,BigDecimal averageCloseHours,long overdueActions,List<DimensionCount> categories,List<DimensionCount> rootCauses,List<DimensionCount> teams,List<IncidentRow> incidents){}
    public record RepeatCandidate(UUID newerIncidentId,String newerIncidentNo,UUID olderIncidentId,String olderIncidentNo,UUID equipmentId,String equipmentCode,String equipmentName,String categoryCode,UUID teamId,int daysApart){}
}
