package com.acme.performance.incident.service;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.common.service.BusinessDeadlineService;
import com.acme.performance.notification.service.NotificationService;
import com.acme.performance.organization.model.BusinessRoles;
import com.acme.performance.organization.service.DataScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class IncidentService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc; private final NotificationService notifications;
    private final BusinessDeadlineService deadlines; private final AuditLogService audit; private final DataScopeService dataScope;
    public IncidentService(JdbcClient jdbc,NotificationService notifications,BusinessDeadlineService deadlines,AuditLogService audit,DataScopeService dataScope){this.jdbc=jdbc;this.notifications=notifications;this.deadlines=deadlines;this.audit=audit;this.dataScope=dataScope;}

    @Transactional
    public IncidentView create(CurrentUser actor,OffsetDateTime occurredAt,UUID lineId,UUID stationId,List<UUID> equipmentIds,String requestId){
        requireCreator(actor);if(occurredAt==null||lineId==null||stationId==null||equipmentIds==null||equipmentIds.isEmpty())invalid();
        List<Responsibility> responsibilities=jdbc.sql("""
                SELECT DISTINCT s.user_id,s.team_id,s.business_date,s.shift_code FROM schedule_assignment s
                WHERE s.line_id=:lineId AND s.station_id=:stationId AND s.status='PUBLISHED'
                  AND :occurredAt>=s.shift_starts_at AND :occurredAt<=s.shift_ends_at
                ORDER BY s.user_id
                """).param("lineId",lineId).param("stationId",stationId).param("occurredAt",occurredAt)
                .query((rs,n)->new Responsibility(rs.getObject("user_id",UUID.class),rs.getObject("team_id",UUID.class),rs.getObject("business_date",LocalDate.class),rs.getString("shift_code"))).list();
        if(responsibilities.isEmpty())throw new ApiException("RESPONSIBILITY_NOT_FOUND","该时间和站位没有已发布责任排班",HttpStatus.CONFLICT);
        UUID teamId=responsibilities.getFirst().teamId();if(actor.roles().contains(BusinessRoles.ASSISTANT_ENGINEER)&&!actor.orgUnitId().equals(teamId))forbidden();
        long validDevices=jdbc.sql("SELECT COUNT(*) FROM equipment WHERE id IN (:ids) AND line_id=:lineId AND station_id=:stationId AND status='ACTIVE'")
                .param("ids",equipmentIds).param("lineId",lineId).param("stationId",stationId).query(Long.class).single();
        if(validDevices!=equipmentIds.stream().distinct().count())throw new ApiException("INVALID_INCIDENT_EQUIPMENT","所选设备不属于对应线体和站位或已经停用",HttpStatus.BAD_REQUEST);
        UUID id=UUID.randomUUID();String no="EX"+DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(occurredAt.atZoneSameInstant(ZONE))+id.toString().substring(0,4).toUpperCase();
        Responsibility primary=responsibilities.getFirst();
        jdbc.sql("""
                INSERT INTO equipment_incident(id,incident_no,occurred_at,business_date,shift_code,team_id,line_id,station_id,status,created_by)
                VALUES (:id,:no,:occurredAt,:date,:shiftCode,:teamId,:lineId,:stationId,'WAITING_STATEMENTS',:actorId)
                """).param("id",id).param("no",no).param("occurredAt",occurredAt).param("date",primary.businessDate())
                .param("shiftCode",primary.shiftCode()).param("teamId",teamId).param("lineId",lineId)
                .param("stationId",stationId).param("actorId",actor.userId()).update();
        equipmentIds.stream().distinct().forEach(equipmentId->jdbc.sql("INSERT INTO incident_equipment(incident_id,equipment_id) VALUES (:incidentId,:equipmentId)").param("incidentId",id).param("equipmentId",equipmentId).update());
        for(Responsibility responsibility:responsibilities){OffsetDateTime due=deadlines.addHours(occurredAt,24,responsibility.userId());UUID statementId=UUID.randomUUID();jdbc.sql("INSERT INTO incident_statement(id,incident_id,responsible_user_id,status,due_at) VALUES (:id,:incidentId,:userId,'PENDING',:dueAt)").param("id",statementId).param("incidentId",id).param("userId",responsibility.userId()).param("dueAt",due).update();notifications.create(responsibility.userId(),"INCIDENT_STATEMENT_REQUIRED","设备异常待填写",no+" 请在24小时内提交个人说明。","EQUIPMENT_INCIDENT",id,false);}
        archiveEvent(actor,id,"INCIDENT_CREATED","EQUIPMENT_INCIDENT",id,requestId);audit.record(actor.userId(),"INCIDENT_CREATE","EQUIPMENT_INCIDENT",id,"{}",requestId);return get(id);
    }

    @Transactional
    public IncidentView submitStatement(CurrentUser actor,UUID incidentId,String phenomenon,String handling,String rootCause,String longTermAction,long version,String requestId){
        requireText(phenomenon,"异常现象");requireText(handling,"处理方法");requireText(rootCause,"问题根因");
        StatementRow row=jdbc.sql("SELECT id,status,version,resubmit_due_at FROM incident_statement WHERE incident_id=:incidentId AND responsible_user_id=:userId FOR UPDATE")
                .param("incidentId",incidentId).param("userId",actor.userId()).query((rs,n)->new StatementRow(rs.getObject("id",UUID.class),rs.getString("status"),rs.getLong("version"),rs.getObject("resubmit_due_at",OffsetDateTime.class))).optional().orElseThrow(()->new ApiException("INCIDENT_STATEMENT_NOT_FOUND","没有需要您填写的异常说明",HttpStatus.NOT_FOUND));
        if(row.version()!=version)conflict();if(!Set.of("PENDING","OVERDUE","RETURNED").contains(row.status()))throw new ApiException("STATEMENT_ALREADY_SUBMITTED","该说明已经提交",HttpStatus.CONFLICT);
        int updated=jdbc.sql("""
                UPDATE incident_statement SET phenomenon=:phenomenon,handling_method=:handling,root_cause=:rootCause,
                  long_term_action=:longTerm,status='SUBMITTED',submitted_at=CURRENT_TIMESTAMP,resubmit_due_at=NULL,
                  version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version
                """).param("phenomenon",phenomenon.trim()).param("handling",handling.trim()).param("rootCause",rootCause.trim())
                .param("longTerm",clean(longTermAction)).param("id",row.id()).param("version",version).update();if(updated!=1)conflict();
        refreshIncidentStatus(incidentId);notifySupervisors(incidentId,"设备异常说明待审核");archiveEvent(actor,incidentId,"STATEMENT_SUBMITTED","INCIDENT_STATEMENT",row.id(),requestId);audit.record(actor.userId(),"INCIDENT_STATEMENT_SUBMIT","INCIDENT_STATEMENT",row.id(),"{}",requestId);return get(incidentId);
    }

    @Transactional
    public IncidentView reviewStatement(CurrentUser actor,UUID statementId,long version,String decision,String comment,String requestId){
        requireSupervisor(actor);String normalized=decision==null?"":decision.toUpperCase();if(!Set.of("APPROVE","RETURN").contains(normalized))invalid();
        ReviewRow row=jdbc.sql("SELECT incident_id,responsible_user_id,status,version FROM incident_statement WHERE id=:id FOR UPDATE").param("id",statementId)
                .query((rs,n)->new ReviewRow(rs.getObject("incident_id",UUID.class),rs.getObject("responsible_user_id",UUID.class),rs.getString("status"),rs.getLong("version"))).optional().orElseThrow(()->new ApiException("STATEMENT_NOT_FOUND","责任人说明不存在",HttpStatus.NOT_FOUND));
        if(!dataScope.canAccessUser(actor,row.userId()))forbidden();if(row.version()!=version)conflict();if(!"SUBMITTED".equals(row.status()))throw new ApiException("STATEMENT_ALREADY_REVIEWED","该说明已经处理",HttpStatus.CONFLICT);
        if("RETURN".equals(normalized)&&(comment==null||comment.isBlank()))throw new ApiException("REVIEW_COMMENT_REQUIRED","退回时必须填写修改要求",HttpStatus.BAD_REQUEST);
        OffsetDateTime resubmit="RETURN".equals(normalized)?deadlines.addHours(OffsetDateTime.now(ZONE),8,row.userId()):null;
        int updated=jdbc.sql("""
                UPDATE incident_statement SET status=:status,reviewed_at=CURRENT_TIMESTAMP,reviewer_id=:actorId,
                  review_comment=:comment,resubmit_due_at=:resubmitDue,version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND version=:version
                """).param("status","APPROVE".equals(normalized)?"APPROVED":"RETURNED").param("actorId",actor.userId())
                .param("comment",clean(comment)).param("resubmitDue",resubmit).param("id",statementId).param("version",version).update();if(updated!=1)conflict();
        notifications.create(row.userId(),"INCIDENT_REVIEW","设备异常说明"+("APPROVE".equals(normalized)?"已通过":"被退回"),"APPROVE".equals(normalized)?"说明已经通过审核。":comment.trim(),"EQUIPMENT_INCIDENT",row.incidentId(),false);
        refreshIncidentStatus(row.incidentId());archiveEvent(actor,row.incidentId(),"STATEMENT_"+("APPROVE".equals(normalized)?"APPROVED":"RETURNED"),"INCIDENT_STATEMENT",statementId,requestId);audit.record(actor.userId(),"INCIDENT_REVIEW_"+normalized,"INCIDENT_STATEMENT",statementId,"{}",requestId);return get(row.incidentId());
    }

    @Transactional
    public IncidentView addNote(CurrentUser actor,UUID incidentId,String content,String requestId){
        requireSupervisor(actor);requireText(content,"追加说明");IncidentView incident=get(incidentId);requireIncidentScope(actor,incident);if(!Set.of("ARCHIVED","VOID").contains(incident.status()))throw new ApiException("INCIDENT_NOT_ARCHIVED","仅已归档异常可以追加说明",HttpStatus.CONFLICT);
        jdbc.sql("INSERT INTO incident_note(id,incident_id,actor_id,content) VALUES (:id,:incidentId,:actorId,:content)").param("id",UUID.randomUUID()).param("incidentId",incidentId).param("actorId",actor.userId()).param("content",content.trim()).update();audit.record(actor.userId(),"INCIDENT_NOTE_ADD","EQUIPMENT_INCIDENT",incidentId,"{}",requestId);return get(incidentId);
    }

    @Transactional
    public IncidentView voidIncident(CurrentUser actor,UUID incidentId,long version,String reason,String requestId){
        requireSupervisor(actor);requireText(reason,"作废原因");IncidentView item=getForUpdate(incidentId);requireIncidentScope(actor,item);if(item.version()!=version)conflict();if(!"ARCHIVED".equals(item.status()))throw new ApiException("INCIDENT_NOT_ARCHIVED","仅已归档异常可以标记作废",HttpStatus.CONFLICT);
        int updated=jdbc.sql("UPDATE equipment_incident SET status='VOID',voided_by=:actorId,void_reason=:reason,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").param("actorId",actor.userId()).param("reason",reason.trim()).param("id",incidentId).param("version",version).update();if(updated!=1)conflict();audit.record(actor.userId(),"INCIDENT_VOID","EQUIPMENT_INCIDENT",incidentId,"{}",requestId);return get(incidentId);
    }

    @Transactional(readOnly=true)
    public List<IncidentView> minePending(CurrentUser actor){List<UUID> ids=jdbc.sql("SELECT i.id FROM equipment_incident i WHERE EXISTS (SELECT 1 FROM incident_statement s WHERE s.incident_id=i.id AND s.responsible_user_id=:userId AND s.status IN ('PENDING','OVERDUE','RETURNED','SUBMITTED')) ORDER BY i.occurred_at DESC,i.id").param("userId",actor.userId()).query(UUID.class).list();return ids.stream().map(this::get).toList();}
    @Transactional(readOnly=true)
    public List<IncidentView> reviewQueue(CurrentUser actor){if(!actor.roles().contains(BusinessRoles.SUPERVISOR)&&!actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER))return List.of();List<UUID> ids=jdbc.sql("SELECT i.id FROM equipment_incident i JOIN incident_statement s ON s.incident_id=i.id JOIN app_user creator ON creator.id=i.created_by WHERE s.status IN ('SUBMITTED','OVERDUE') AND creator.is_review_account=:reviewAccount GROUP BY i.id ORDER BY MIN(COALESCE(s.resubmit_due_at,s.due_at)),MIN(i.occurred_at)").param("reviewAccount",isReviewAccount(actor)).query(UUID.class).list();return ids.stream().map(this::get).filter(item->dataScope.canAccessOrg(actor,item.teamId())).toList();}

    @Transactional(readOnly=true)
    public List<IncidentView> openDossiers(CurrentUser actor){if(actor.roles().stream().noneMatch(Set.of(BusinessRoles.ASSISTANT_ENGINEER,BusinessRoles.SUPERVISOR,BusinessRoles.DEPARTMENT_MANAGER)::contains)&&!actor.administrator())return List.of();List<UUID> ids=jdbc.sql("SELECT i.id FROM equipment_incident i JOIN app_user creator ON creator.id=i.created_by WHERE i.status NOT IN ('ARCHIVED','VOID') AND creator.is_review_account=:reviewAccount ORDER BY i.occurred_at DESC,i.id DESC LIMIT 200").param("reviewAccount",isReviewAccount(actor)).query(UUID.class).list();return ids.stream().map(this::get).filter(item->dataScope.canAccessOrg(actor,item.teamId())).toList();}

    @Transactional(readOnly=true)
    public List<IncidentView> archive(CurrentUser actor,List<UUID> equipmentIds,UUID lineId,UUID responsibleId,LocalDate from,LocalDate to,String keyword,int limit){
        int safeLimit=Math.max(1,Math.min(limit,200));StringBuilder sql=new StringBuilder("SELECT DISTINCT i.id,i.occurred_at FROM equipment_incident i JOIN app_user creator ON creator.id=i.created_by LEFT JOIN incident_equipment ie ON ie.incident_id=i.id LEFT JOIN incident_statement s ON s.incident_id=i.id LEFT JOIN equipment e ON e.id=ie.equipment_id WHERE i.status IN ('ARCHIVED','VOID') AND creator.is_review_account=:reviewAccount");Map<String,Object> params=new LinkedHashMap<>();params.put("reviewAccount",isReviewAccount(actor));
        if(equipmentIds!=null&&!equipmentIds.isEmpty()){sql.append(" AND ie.equipment_id IN (:equipmentIds)");params.put("equipmentIds",equipmentIds);}if(lineId!=null){sql.append(" AND i.line_id=:lineId");params.put("lineId",lineId);}if(responsibleId!=null){sql.append(" AND s.responsible_user_id=:responsibleId");params.put("responsibleId",responsibleId);}if(from!=null){sql.append(" AND i.business_date>=:from");params.put("from",from);}if(to!=null){sql.append(" AND i.business_date<=:to");params.put("to",to);}if(keyword!=null&&!keyword.isBlank()){sql.append(" AND (i.incident_no ILIKE :keyword OR e.name ILIKE :keyword OR s.phenomenon ILIKE :keyword OR s.handling_method ILIKE :keyword)");params.put("keyword","%"+keyword.trim()+"%");}sql.append(" ORDER BY i.occurred_at DESC,i.id DESC LIMIT :limit");params.put("limit",safeLimit);var spec=jdbc.sql(sql.toString());for(var entry:params.entrySet())spec=spec.param(entry.getKey(),entry.getValue());return spec.query((rs,n)->rs.getObject("id",UUID.class)).list().stream().map(this::get).filter(item->canView(actor,item)).toList();
    }

    @Transactional(readOnly=true)
    public SearchResult search(CurrentUser actor,List<UUID> equipmentIds,UUID lineId,LocalDate from,LocalDate to,String keyword,String status,int page,int size){
        List<SearchRow> rows=searchRows(actor,equipmentIds,lineId,from,to,keyword,status);
        int pageSize=Math.max(1,Math.min(size,100));
        int currentPage=Math.max(0,Math.min(page,Math.max(0,(rows.size()-1)/pageSize)));
        List<IncidentView> items=rows.stream().skip((long)currentPage*pageSize).limit(pageSize).map(row->get(row.id())).toList();
        long archived=rows.stream().filter(row->row.status().equals("ARCHIVED")).count();
        long voided=rows.stream().filter(row->row.status().equals("VOID")).count();
        return new SearchResult(items,rows.size(),currentPage,pageSize,archived,voided,rows.size()-archived-voided);
    }
    @Transactional(readOnly=true)
    public List<IncidentView> exportRecords(CurrentUser actor,List<UUID> equipmentIds,UUID lineId,LocalDate from,LocalDate to,String keyword,String status){
        return searchRows(actor,equipmentIds,lineId,from,to,keyword,status).stream().map(row->get(row.id())).toList();
    }
    private List<SearchRow> searchRows(CurrentUser actor,List<UUID> equipmentIds,UUID lineId,LocalDate from,LocalDate to,String keyword,String status){
        if(from!=null&&to!=null&&from.isAfter(to))throw new ApiException("INVALID_DATE_RANGE","结束日期不能早于开始日期",HttpStatus.BAD_REQUEST);
        String selectedStatus=status==null?"ALL":status;
        if(!Set.of("ALL","OPEN","ARCHIVED","VOID").contains(selectedStatus))throw new ApiException("INVALID_STATUS","无效的异常状态",HttpStatus.BAD_REQUEST);
        StringBuilder sql=new StringBuilder("SELECT i.id,i.team_id,i.status,(i.created_by=:actorId OR EXISTS (SELECT 1 FROM incident_statement own WHERE own.incident_id=i.id AND own.responsible_user_id=:actorId)) AS involved FROM equipment_incident i JOIN app_user creator ON creator.id=i.created_by WHERE creator.is_review_account=:reviewAccount");
        Map<String,Object> params=new LinkedHashMap<>();params.put("actorId",actor.userId());params.put("reviewAccount",isReviewAccount(actor));
        if(selectedStatus.equals("OPEN"))sql.append(" AND i.status NOT IN ('ARCHIVED','VOID')");
        else if(!selectedStatus.equals("ALL")){sql.append(" AND i.status=:status");params.put("status",selectedStatus);}
        if(lineId!=null){sql.append(" AND i.line_id=:lineId");params.put("lineId",lineId);}
        if(from!=null){sql.append(" AND i.business_date>=:from");params.put("from",from);}
        if(to!=null){sql.append(" AND i.business_date<=:to");params.put("to",to);}
        if(equipmentIds!=null&&!equipmentIds.isEmpty()){sql.append(" AND EXISTS (SELECT 1 FROM incident_equipment ie WHERE ie.incident_id=i.id AND ie.equipment_id IN (:equipmentIds))");params.put("equipmentIds",equipmentIds);}
        if(keyword!=null&&!keyword.isBlank()){
            sql.append(" AND (i.incident_no ILIKE :keyword OR EXISTS (SELECT 1 FROM incident_equipment ie JOIN equipment e ON e.id=ie.equipment_id WHERE ie.incident_id=i.id AND e.name ILIKE :keyword) OR EXISTS (SELECT 1 FROM incident_statement s WHERE s.incident_id=i.id AND (s.phenomenon ILIKE :keyword OR s.handling_method ILIKE :keyword OR s.root_cause ILIKE :keyword OR s.long_term_action ILIKE :keyword)))");
            params.put("keyword","%"+keyword.trim()+"%");
        }
        sql.append(" ORDER BY i.occurred_at DESC,i.id DESC");
        var query=jdbc.sql(sql.toString());for(var entry:params.entrySet())query=query.param(entry.getKey(),entry.getValue());
        Map<UUID,Boolean> scopes=new HashMap<>();
        return query.query((result,index)->new SearchRow(result.getObject("id",UUID.class),result.getObject("team_id",UUID.class),result.getString("status"),result.getBoolean("involved"))).list().stream().filter(row->row.involved()||scopes.computeIfAbsent(row.teamId(),team->dataScope.canAccessOrg(actor,team))).toList();
    }
    private record SearchRow(UUID id,UUID teamId,String status,boolean involved){}
    public record SearchResult(List<IncidentView> items,long total,int page,int size,long archived,long voided,long open){}

    @Transactional(readOnly=true) public IncidentView getVisible(CurrentUser actor,UUID id){IncidentView item=get(id);boolean reviewData=jdbc.sql("SELECT u.is_review_account FROM equipment_incident i JOIN app_user u ON u.id=i.created_by WHERE i.id=:id").param("id",id).query(Boolean.class).single();if(reviewData!=isReviewAccount(actor)||!canView(actor,item))forbidden();return item;}

    private void refreshIncidentStatus(UUID incidentId){List<String> states=jdbc.sql("SELECT status FROM incident_statement WHERE incident_id=:id").param("id",incidentId).query(String.class).list();String status;if(states.stream().allMatch("APPROVED"::equals))status="INVESTIGATING";else if(states.stream().anyMatch("RETURNED"::equals))status="RETURNED";else if(states.stream().allMatch(s->Set.of("SUBMITTED","APPROVED").contains(s)))status="UNDER_REVIEW";else status="WAITING_STATEMENTS";jdbc.sql("UPDATE equipment_incident SET status=:status,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status NOT IN ('ARCHIVED','VOID')").param("status",status).param("id",incidentId).update();}
    private void notifySupervisors(UUID incidentId,String title){jdbc.sql("SELECT DISTINCT rb.user_id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE'").query(UUID.class).list().forEach(userId->notifications.create(userId,"INCIDENT_REVIEW_REQUIRED",title,"请在24小时内完成审核。","EQUIPMENT_INCIDENT",incidentId,false));}

    private IncidentView getForUpdate(UUID id){jdbc.sql("SELECT id FROM equipment_incident WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().orElseThrow(()->new ApiException("INCIDENT_NOT_FOUND","异常单不存在",HttpStatus.NOT_FOUND));return get(id);}
    private IncidentView get(UUID id){Base base=jdbc.sql("""
            SELECT i.id,i.incident_no,i.occurred_at,i.business_date,i.shift_code,i.team_id,team.name team_name,
              i.line_id,l.name line_name,i.station_id,st.name station_name,i.status,i.created_by,creator.display_name creator_name,
              i.reviewer_id,reviewer.display_name reviewer_name,i.review_comment,i.archived_at,i.voided_by,i.void_reason,
              i.version,i.created_at,i.updated_at FROM equipment_incident i JOIN org_unit team ON team.id=i.team_id
              JOIN production_line l ON l.id=i.line_id JOIN station st ON st.id=i.station_id JOIN app_user creator ON creator.id=i.created_by
              LEFT JOIN app_user reviewer ON reviewer.id=i.reviewer_id WHERE i.id=:id
            """).param("id",id).query((rs,n)->new Base(rs.getObject("id",UUID.class),rs.getString("incident_no"),rs.getObject("occurred_at",OffsetDateTime.class),rs.getObject("business_date",LocalDate.class),rs.getString("shift_code"),rs.getObject("team_id",UUID.class),rs.getString("team_name"),rs.getObject("line_id",UUID.class),rs.getString("line_name"),rs.getObject("station_id",UUID.class),rs.getString("station_name"),rs.getString("status"),rs.getObject("created_by",UUID.class),rs.getString("creator_name"),rs.getObject("reviewer_id",UUID.class),rs.getString("reviewer_name"),rs.getString("review_comment"),rs.getObject("archived_at",OffsetDateTime.class),rs.getObject("voided_by",UUID.class),rs.getString("void_reason"),rs.getLong("version"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("updated_at",OffsetDateTime.class))).optional().orElseThrow(()->new ApiException("INCIDENT_NOT_FOUND","异常单不存在",HttpStatus.NOT_FOUND));
        List<DeviceItem> devices=jdbc.sql("SELECT e.id,e.code,e.name,e.category FROM incident_equipment ie JOIN equipment e ON e.id=ie.equipment_id WHERE ie.incident_id=:id ORDER BY e.code").param("id",id).query((rs,n)->new DeviceItem(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getString("category"))).list();
        List<StatementView> statements=jdbc.sql("SELECT s.id,s.responsible_user_id,u.employee_no,u.display_name,s.phenomenon,s.handling_method,s.root_cause,s.long_term_action,s.status,s.due_at,s.resubmit_due_at,s.submitted_at,s.reviewed_at,s.reviewer_id,r.display_name reviewer_name,s.review_comment,s.version FROM incident_statement s JOIN app_user u ON u.id=s.responsible_user_id LEFT JOIN app_user r ON r.id=s.reviewer_id WHERE s.incident_id=:id ORDER BY u.employee_no").param("id",id).query((rs,n)->new StatementView(rs.getObject("id",UUID.class),rs.getObject("responsible_user_id",UUID.class),rs.getString("employee_no"),rs.getString("display_name"),rs.getString("phenomenon"),rs.getString("handling_method"),rs.getString("root_cause"),rs.getString("long_term_action"),rs.getString("status"),rs.getObject("due_at",OffsetDateTime.class),rs.getObject("resubmit_due_at",OffsetDateTime.class),rs.getObject("submitted_at",OffsetDateTime.class),rs.getObject("reviewed_at",OffsetDateTime.class),rs.getObject("reviewer_id",UUID.class),rs.getString("reviewer_name"),rs.getString("review_comment"),rs.getLong("version"))).list();
        List<NoteView> notes=jdbc.sql("SELECT n.id,n.actor_id,u.display_name,n.content,n.created_at FROM incident_note n JOIN app_user u ON u.id=n.actor_id WHERE n.incident_id=:id ORDER BY n.created_at").param("id",id).query((rs,n)->new NoteView(rs.getObject("id",UUID.class),rs.getObject("actor_id",UUID.class),rs.getString("display_name"),rs.getString("content"),rs.getObject("created_at",OffsetDateTime.class))).list();
        return new IncidentView(base.id(),base.no(),base.occurredAt(),base.businessDate(),base.shiftCode(),base.teamId(),base.teamName(),base.lineId(),base.lineName(),base.stationId(),base.stationName(),base.status(),base.createdBy(),base.creatorName(),base.reviewerId(),base.reviewerName(),base.reviewComment(),base.archivedAt(),base.voidedBy(),base.voidReason(),devices,statements,notes,base.version(),base.createdAt(),base.updatedAt());}

    @Transactional
    public IncidentView followUp(CurrentUser actor,UUID statementId,long version,UUID owner,OffsetDateTime dueAt,String reason,String requestId){
        requireSupervisor(actor);requireText(reason,"补交安排原因");
        if(owner==null||dueAt==null||!dueAt.isAfter(OffsetDateTime.now(ZONE)))invalid();
        ReviewRow row=jdbc.sql("SELECT incident_id,responsible_user_id,status,version FROM incident_statement WHERE id=:id FOR UPDATE").param("id",statementId)
            .query((rs,n)->new ReviewRow(rs.getObject("incident_id",UUID.class),rs.getObject("responsible_user_id",UUID.class),rs.getString("status"),rs.getLong("version"))).optional().orElseThrow(()->new ApiException("STATEMENT_NOT_FOUND","责任人说明不存在",HttpStatus.NOT_FOUND));
        requireIncidentScope(actor,get(row.incidentId()));if(!dataScope.canAccessUser(actor,owner))forbidden();
        if(row.version()!=version)conflict();
        if(!Set.of("PENDING","OVERDUE","RETURNED").contains(row.status()))throw new ApiException("FOLLOW_UP_NOT_ALLOWED","仅未提交、逾期或被退回的说明可以安排补交",HttpStatus.CONFLICT);
        long eligible=jdbc.sql("SELECT count(*) FROM app_user u WHERE u.id=:id AND u.status='ACTIVE' AND EXISTS(SELECT 1 FROM role_binding r WHERE r.user_id=u.id AND r.role_code IN ('TECHNICIAN','ASSISTANT_ENGINEER')) AND u.is_review_account=(SELECT is_review_account FROM app_user WHERE id=:actor)")
            .param("id",owner).param("actor",actor.userId()).query(Long.class).single();if(eligible!=1)invalid();
        long duplicate=jdbc.sql("SELECT count(*) FROM incident_statement WHERE incident_id=:incident AND responsible_user_id=:owner AND id<>:id").param("incident",row.incidentId()).param("owner",owner).param("id",statementId).query(Long.class).single();
        if(duplicate>0)throw new ApiException("STATEMENT_OWNER_EXISTS","该人员已有本次异常说明任务，请选择其他责任人",HttpStatus.CONFLICT);
        jdbc.sql("UPDATE incident_statement SET responsible_user_id=:owner,status='RETURNED',resubmit_due_at=:due,review_comment=:reason,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("owner",owner).param("due",dueAt).param("reason",reason.trim()).param("id",statementId).update();
        String detail="原责任人="+row.userId()+"；原状态="+row.status()+"；补交责任人="+owner+"；新截止="+dueAt+"；原因="+reason.trim();
        jdbc.sql("INSERT INTO incident_note(id,incident_id,actor_id,content) VALUES (:id,:incident,:actor,:content)").param("id",UUID.randomUUID()).param("incident",row.incidentId()).param("actor",actor.userId()).param("content",detail).update();
        archiveEvent(actor,row.incidentId(),"STATEMENT_FOLLOW_UP","INCIDENT_STATEMENT",statementId,requestId);
        audit.record(actor.userId(),"INCIDENT_STATEMENT_FOLLOW_UP","INCIDENT_STATEMENT",statementId,"{}",requestId);
        notifications.create(owner,"INCIDENT_FOLLOW_UP","异常说明补交安排",reason.trim()+"；请于 "+dueAt+" 前提交。","EQUIPMENT_INCIDENT",row.incidentId(),false);
        return get(row.incidentId());
    }
    private void requireCreator(CurrentUser actor){if(!actor.roles().contains(BusinessRoles.ASSISTANT_ENGINEER)&&!actor.roles().contains(BusinessRoles.SUPERVISOR))forbidden();}
    private void requireSupervisor(CurrentUser actor){if(!actor.roles().contains(BusinessRoles.SUPERVISOR))forbidden();}
    private void requireIncidentScope(CurrentUser actor,IncidentView item){if(!dataScope.canAccessOrg(actor,item.teamId()))forbidden();}
    private boolean canView(CurrentUser actor,IncidentView item){boolean involved=item.createdBy().equals(actor.userId())||item.statements().stream().anyMatch(s->s.responsibleUserId().equals(actor.userId()));return involved||dataScope.canAccessOrg(actor,item.teamId());}
    private boolean isReviewAccount(CurrentUser actor){return "wxreview".equalsIgnoreCase(actor.employeeNo());}
    private void archiveEvent(CurrentUser actor,UUID incidentId,String type,String objectType,UUID objectId,String requestId){jdbc.sql("INSERT INTO incident_archive_event(id,incident_id,event_type,actor_id,actor_roles,actor_org_id,object_type,object_id,request_id) VALUES (:id,:incidentId,:type,:actorId,:roles,:orgId,:objectType,:objectId,:requestId)").param("id",UUID.randomUUID()).param("incidentId",incidentId).param("type",type).param("actorId",actor.userId()).param("roles",String.join(",",actor.roles())).param("orgId",actor.orgUnitId()).param("objectType",objectType).param("objectId",objectId).param("requestId",requestId).update();}
    private void requireText(String value,String label){if(value==null||value.isBlank()||value.length()>2000)throw new ApiException("INVALID_INCIDENT_TEXT",label+"为必填且不能超过2000字",HttpStatus.BAD_REQUEST);}
    private String clean(String v){return v==null||v.isBlank()?null:v.trim();}
    private void invalid(){throw new ApiException("INVALID_INCIDENT","异常单内容不完整或不合法",HttpStatus.BAD_REQUEST);}
    private void forbidden(){throw new ApiException("FORBIDDEN","当前角色或数据范围无权执行此操作",HttpStatus.FORBIDDEN);}
    private void conflict(){throw new ApiException("VERSION_CONFLICT","异常信息已被其他人处理，请刷新后重试",HttpStatus.CONFLICT);}

    private record Responsibility(UUID userId,UUID teamId,LocalDate businessDate,String shiftCode){}
    private record StatementRow(UUID id,String status,long version,OffsetDateTime resubmitDueAt){}
    private record ReviewRow(UUID incidentId,UUID userId,String status,long version){}
    private record Base(UUID id,String no,OffsetDateTime occurredAt,LocalDate businessDate,String shiftCode,UUID teamId,String teamName,UUID lineId,String lineName,UUID stationId,String stationName,String status,UUID createdBy,String creatorName,UUID reviewerId,String reviewerName,String reviewComment,OffsetDateTime archivedAt,UUID voidedBy,String voidReason,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
    public record DeviceItem(UUID id,String code,String name,String category){}
    public record StatementView(UUID id,UUID responsibleUserId,String employeeNo,String responsibleName,String phenomenon,String handlingMethod,String rootCause,String longTermAction,String status,OffsetDateTime dueAt,OffsetDateTime resubmitDueAt,OffsetDateTime submittedAt,OffsetDateTime reviewedAt,UUID reviewerId,String reviewerName,String reviewComment,long version){}
    public record NoteView(UUID id,UUID actorId,String actorName,String content,OffsetDateTime createdAt){}
    public record IncidentView(UUID id,String incidentNo,OffsetDateTime occurredAt,LocalDate businessDate,String shiftCode,UUID teamId,String teamName,UUID lineId,String lineName,UUID stationId,String stationName,String status,UUID createdBy,String creatorName,UUID reviewerId,String reviewerName,String reviewComment,OffsetDateTime archivedAt,UUID voidedBy,String voidReason,List<DeviceItem> devices,List<StatementView> statements,List<NoteView> notes,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
}
