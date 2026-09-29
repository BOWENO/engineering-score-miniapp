package com.acme.performance.incident.service;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.organization.model.BusinessRoles;
import com.acme.performance.organization.service.DataScopeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class IncidentDossierService {
    private final JdbcClient jdbc;
    private final DataScopeService dataScope;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public IncidentDossierService(JdbcClient jdbc, DataScopeService dataScope, AuditLogService audit, ObjectMapper mapper) {
        this.jdbc = jdbc; this.dataScope = dataScope; this.audit = audit; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public DossierView get(CurrentUser actor, UUID incidentId) {
        IncidentBase base = requireVisible(actor, incidentId);
        InvestigationView investigation = jdbc.sql("""
                SELECT id,mode,lead_user_id,direct_cause,root_cause,root_cause_category,five_whys::text,
                  conclusion,status,confirmed_by,confirmed_at,version,created_at,updated_at
                FROM incident_investigation WHERE incident_id=:incidentId
                """).param("incidentId", incidentId).query((rs,n)->new InvestigationView(
                rs.getObject("id",UUID.class),rs.getString("mode"),rs.getObject("lead_user_id",UUID.class),
                rs.getString("direct_cause"),rs.getString("root_cause"),rs.getString("root_cause_category"),
                rs.getString("five_whys"),rs.getString("conclusion"),rs.getString("status"),
                rs.getObject("confirmed_by",UUID.class),rs.getObject("confirmed_at",OffsetDateTime.class),
                rs.getLong("version"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("updated_at",OffsetDateTime.class)
        )).optional().orElse(null);
        List<EvidenceView> evidence = jdbc.sql("""
                SELECT ie.id,ie.attachment_id,ie.evidence_type,ie.description,ie.uploaded_by,u.display_name,
                  a.mime_type,a.size_bytes,ie.status,ie.created_at
                FROM incident_evidence ie JOIN attachment a ON a.id=ie.attachment_id
                JOIN app_user u ON u.id=ie.uploaded_by WHERE ie.incident_id=:incidentId ORDER BY ie.created_at,ie.id
                """).param("incidentId",incidentId).query((rs,n)->new EvidenceView(rs.getObject("id",UUID.class),
                rs.getObject("attachment_id",UUID.class),rs.getString("evidence_type"),rs.getString("description"),
                rs.getObject("uploaded_by",UUID.class),rs.getString("display_name"),rs.getString("mime_type"),
                rs.getLong("size_bytes"),rs.getString("status"),rs.getObject("created_at",OffsetDateTime.class))).list();
        List<ResponsibilityView> responsibilities = jdbc.sql("""
                SELECT r.id,r.responsible_user_id,u.display_name,r.responsible_org_id,o.name,r.responsibility_type,
                  r.responsibility_percent,r.basis,r.proposed_action,r.status,r.version,r.created_at
                FROM incident_responsibility r LEFT JOIN app_user u ON u.id=r.responsible_user_id
                LEFT JOIN org_unit o ON o.id=r.responsible_org_id WHERE r.incident_id=:incidentId ORDER BY r.created_at,r.id
                """).param("incidentId",incidentId).query((rs,n)->new ResponsibilityView(rs.getObject("id",UUID.class),
                rs.getObject("responsible_user_id",UUID.class),rs.getString(3),rs.getObject("responsible_org_id",UUID.class),
                rs.getString(5),rs.getString("responsibility_type"),(Integer)rs.getObject("responsibility_percent"),
                rs.getString("basis"),rs.getString("proposed_action"),rs.getString("status"),rs.getLong("version"),
                rs.getObject("created_at",OffsetDateTime.class))).list();
        List<ActionView> actions = actions(incidentId);
        List<EventView> events = jdbc.sql("""
                SELECT e.id,e.event_type,e.actor_id,u.display_name,e.object_type,e.object_id,e.detail::text,e.request_id,e.created_at
                FROM incident_archive_event e JOIN app_user u ON u.id=e.actor_id
                WHERE e.incident_id=:incidentId ORDER BY e.created_at,e.id
                """).param("incidentId",incidentId).query((rs,n)->new EventView(rs.getObject("id",UUID.class),
                rs.getString("event_type"),rs.getObject("actor_id",UUID.class),rs.getString("display_name"),
                rs.getString("object_type"),rs.getObject("object_id",UUID.class),rs.getString("detail"),
                rs.getString("request_id"),rs.getObject("created_at",OffsetDateTime.class))).list();
        ArchiveReadiness readiness = readiness(incidentId);
        return new DossierView(base.id(),base.incidentNo(),base.status(),base.teamId(),base.severity(),base.categoryCode(),
                base.impactLevel(),base.downtimeMinutes(),base.impactDescription(),base.performanceRequired(),base.version(),
                investigation,evidence,responsibilities,actions,events,readiness);
    }

    @Transactional(readOnly = true)
    public List<DossierSummary> openDossiers(CurrentUser actor) {
        boolean review="wxreview".equalsIgnoreCase(actor.employeeNo());
        return jdbc.sql("""
                SELECT i.id,i.incident_no,i.status,i.team_id,t.name team_name,i.severity,i.category_code,i.impact_level,
                  i.downtime_minutes,i.occurred_at,COUNT(a.id) FILTER (WHERE a.status NOT IN ('ACCEPTED','CANCELLED')) open_actions
                FROM equipment_incident i JOIN org_unit t ON t.id=i.team_id JOIN app_user creator ON creator.id=i.created_by
                LEFT JOIN incident_corrective_action a ON a.incident_id=i.id
                WHERE i.status NOT IN ('ARCHIVED','VOID') AND creator.is_review_account=:review
                GROUP BY i.id,t.name ORDER BY i.occurred_at DESC,i.id DESC LIMIT 200
                """).param("review",review).query((rs,n)->new DossierSummary(rs.getObject("id",UUID.class),
                rs.getString("incident_no"),rs.getString("status"),rs.getObject("team_id",UUID.class),rs.getString("team_name"),
                rs.getString("severity"),rs.getString("category_code"),rs.getString("impact_level"),rs.getInt("downtime_minutes"),
                rs.getObject("occurred_at",OffsetDateTime.class),rs.getLong("open_actions"))).list().stream()
                .filter(item->dataScope.canAccessOrg(actor,item.teamId())).toList();
    }

    @Transactional
    public DossierView updateMetadata(CurrentUser actor, UUID incidentId, MetadataInput input, long version, String requestId) {
        requireManager(actor); IncidentBase base=requireVisibleForUpdate(actor,incidentId); if(base.version()!=version) conflict();
        String severity=upper(input.severity()); String impact=upper(input.impactLevel());
        if(!Set.of("GENERAL","IMPORTANT","MAJOR").contains(severity)||!Set.of("LOW","MEDIUM","HIGH").contains(impact)
                ||input.categoryCode()==null||input.categoryCode().isBlank()||input.downtimeMinutes()<0) invalid();
        int updated=jdbc.sql("""
                UPDATE equipment_incident SET severity=:severity,category_code=:category,impact_level=:impact,
                  downtime_minutes=:downtime,impact_description=:description,performance_required=:performanceRequired,
                  version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status NOT IN ('ARCHIVED','VOID')
                """).param("severity",severity).param("category",input.categoryCode().trim()).param("impact",impact)
                .param("downtime",input.downtimeMinutes()).param("description",clean(input.impactDescription()))
                .param("performanceRequired",input.performanceRequired()).param("id",incidentId).param("version",version).update();
        if(updated!=1) conflict(); event(actor,incidentId,"METADATA_UPDATED","EQUIPMENT_INCIDENT",incidentId,input,requestId);
        return get(actor,incidentId);
    }

    @Transactional
    public DossierView addEvidence(CurrentUser actor, UUID incidentId, UUID attachmentId, String type, String description, String requestId) {
        IncidentBase base=requireVisible(actor,incidentId); if(Set.of("ARCHIVED","VOID").contains(base.status())) state();
        long owned=jdbc.sql("SELECT COUNT(*) FROM attachment WHERE id=:attachmentId AND owner_id=:actorId")
                .param("attachmentId",attachmentId).param("actorId",actor.userId()).query(Long.class).single();
        if(owned==0) forbidden(); UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO incident_evidence(id,incident_id,attachment_id,evidence_type,description,uploaded_by) VALUES (:id,:incidentId,:attachmentId,:type,:description,:actorId)")
                .param("id",id).param("incidentId",incidentId).param("attachmentId",attachmentId)
                .param("type",required(type,32)).param("description",clean(description)).param("actorId",actor.userId()).update();
        event(actor,incidentId,"EVIDENCE_ADDED","INCIDENT_EVIDENCE",id,new EvidenceInput(attachmentId,type,description),requestId);
        return get(actor,incidentId);
    }

    @Transactional
    public DossierView saveInvestigation(CurrentUser actor, UUID incidentId, InvestigationInput input, Long version, String requestId) {
        requireManager(actor); IncidentBase base=requireVisibleForUpdate(actor,incidentId);
        if(!Set.of("INVESTIGATING","RESPONSIBILITY_PENDING").contains(base.status())) state();
        String mode=upper(input.mode()); if(!Set.of("SIMPLE","FULL").contains(mode)) invalid();
        UUID existing=jdbc.sql("SELECT id FROM incident_investigation WHERE incident_id=:incidentId")
                .param("incidentId",incidentId).query(UUID.class).optional().orElse(null);
        UUID id=existing==null?UUID.randomUUID():existing;
        if(existing==null){
            jdbc.sql("""
                    INSERT INTO incident_investigation(id,incident_id,mode,lead_user_id,direct_cause,root_cause,
                      root_cause_category,five_whys,conclusion,status)
                    VALUES (:id,:incidentId,:mode,:actorId,:directCause,:rootCause,:category,CAST(:fiveWhys AS jsonb),:conclusion,'SUBMITTED')
                    """).param("id",id).param("incidentId",incidentId).param("mode",mode).param("actorId",actor.userId())
                    .param("directCause",required(input.directCause(),2000)).param("rootCause",required(input.rootCause(),2000))
                    .param("category",required(input.rootCauseCategory(),64)).param("fiveWhys",json(input.fiveWhys()))
                    .param("conclusion",required(input.conclusion(),2000)).update();
        } else {
            if(version==null) conflict(); int updated=jdbc.sql("""
                    UPDATE incident_investigation SET mode=:mode,direct_cause=:directCause,root_cause=:rootCause,
                      root_cause_category=:category,five_whys=CAST(:fiveWhys AS jsonb),conclusion=:conclusion,status='SUBMITTED',
                      version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status IN ('SUBMITTED','RETURNED')
                    """).param("mode",mode).param("directCause",required(input.directCause(),2000))
                    .param("rootCause",required(input.rootCause(),2000)).param("category",required(input.rootCauseCategory(),64))
                    .param("fiveWhys",json(input.fiveWhys())).param("conclusion",required(input.conclusion(),2000))
                    .param("id",id).param("version",version).update(); if(updated!=1) conflict();
        }
        event(actor,incidentId,"INVESTIGATION_SUBMITTED","INCIDENT_INVESTIGATION",id,input,requestId); return get(actor,incidentId);
    }

    @Transactional
    public DossierView confirmInvestigation(CurrentUser actor, UUID incidentId, long version, String requestId) {
        requireSupervisor(actor); requireVisibleForUpdate(actor,incidentId);
        int updated=jdbc.sql("""
                UPDATE incident_investigation SET status='CONFIRMED',confirmed_by=:actorId,confirmed_at=CURRENT_TIMESTAMP,
                  version=version+1,updated_at=CURRENT_TIMESTAMP WHERE incident_id=:incidentId AND version=:version AND status='SUBMITTED'
                """).param("actorId",actor.userId()).param("incidentId",incidentId).param("version",version).update();
        if(updated!=1) conflict(); jdbc.sql("UPDATE equipment_incident SET status='RESPONSIBILITY_PENDING',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='INVESTIGATING'").param("id",incidentId).update();
        event(actor,incidentId,"INVESTIGATION_CONFIRMED","INCIDENT_INVESTIGATION",null,"{}",requestId); return get(actor,incidentId);
    }

    @Transactional
    public DossierView addResponsibility(CurrentUser actor, UUID incidentId, ResponsibilityInput input, String requestId) {
        requireSupervisor(actor); IncidentBase base=requireVisibleForUpdate(actor,incidentId); if(!"RESPONSIBILITY_PENDING".equals(base.status())) state();
        if(input.responsibleUserId()!=null&&!dataScope.canAccessUser(actor,input.responsibleUserId())) forbidden();
        UUID id=UUID.randomUUID(); jdbc.sql("""
                INSERT INTO incident_responsibility(id,incident_id,responsible_user_id,responsible_org_id,responsibility_type,
                  responsibility_percent,basis,proposed_action,status,created_by)
                VALUES (:id,:incidentId,:userId,:orgId,:type,:percent,:basis,:action,'CONFIRMED',:actorId)
                """).param("id",id).param("incidentId",incidentId).param("userId",input.responsibleUserId())
                .param("orgId",input.responsibleOrgId()).param("type",required(input.responsibilityType(),32))
                .param("percent",input.responsibilityPercent()).param("basis",required(input.basis(),2000))
                .param("action",clean(input.proposedAction())).param("actorId",actor.userId()).update();
        event(actor,incidentId,"RESPONSIBILITY_CONFIRMED","INCIDENT_RESPONSIBILITY",id,input,requestId); return get(actor,incidentId);
    }

    @Transactional
    public DossierView addAction(CurrentUser actor, UUID incidentId, CorrectiveActionInput input, String requestId) {
        requireSupervisor(actor); IncidentBase base=requireVisibleForUpdate(actor,incidentId);
        if(!Set.of("RESPONSIBILITY_PENDING","CORRECTING").contains(base.status())||!dataScope.canAccessUser(actor,input.ownerId())) state();
        UUID id=UUID.randomUUID(); jdbc.sql("""
                INSERT INTO incident_corrective_action(id,incident_id,action_type,content,owner_id,due_at,status)
                VALUES (:id,:incidentId,:type,:content,:ownerId,:dueAt,'PENDING')
                """).param("id",id).param("incidentId",incidentId).param("type",required(input.actionType(),32))
                .param("content",required(input.content(),2000)).param("ownerId",input.ownerId()).param("dueAt",input.dueAt()).update();
        jdbc.sql("UPDATE equipment_incident SET status='CORRECTING',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='RESPONSIBILITY_PENDING'").param("id",incidentId).update();
        event(actor,incidentId,"CORRECTIVE_ACTION_CREATED","INCIDENT_CORRECTIVE_ACTION",id,input,requestId); return get(actor,incidentId);
    }

    @Transactional
    public DossierView completeAction(CurrentUser actor, UUID actionId, long version, String note, String requestId) {
        ActionOwner row=jdbc.sql("SELECT incident_id,owner_id FROM incident_corrective_action WHERE id=:id")
                .param("id",actionId).query((rs,n)->new ActionOwner(rs.getObject("incident_id",UUID.class),rs.getObject("owner_id",UUID.class)))
                .optional().orElseThrow(()->new ApiException("ACTION_NOT_FOUND","整改措施不存在",HttpStatus.NOT_FOUND));
        if(!row.ownerId().equals(actor.userId())) forbidden(); requireVisible(actor,row.incidentId());
        int updated=jdbc.sql("""
                UPDATE incident_corrective_action SET status='PENDING_ACCEPTANCE',completion_note=:note,completed_at=CURRENT_TIMESTAMP,
                  version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status IN ('PENDING','IN_PROGRESS','RETURNED')
                """).param("note",required(note,2000)).param("id",actionId).param("version",version).update(); if(updated!=1) conflict();
        jdbc.sql("UPDATE equipment_incident SET status='PENDING_ACCEPTANCE',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND NOT EXISTS (SELECT 1 FROM incident_corrective_action WHERE incident_id=:id AND status IN ('PENDING','IN_PROGRESS','RETURNED'))").param("id",row.incidentId()).update();
        event(actor,row.incidentId(),"CORRECTIVE_ACTION_COMPLETED","INCIDENT_CORRECTIVE_ACTION",actionId,"{}",requestId); return get(actor,row.incidentId());
    }

    @Transactional
    public DossierView acceptAction(CurrentUser actor, UUID actionId, long version, String decision, String comment, String requestId) {
        requireSupervisor(actor); ActionOwner row=jdbc.sql("SELECT incident_id,owner_id FROM incident_corrective_action WHERE id=:id")
                .param("id",actionId).query((rs,n)->new ActionOwner(rs.getObject("incident_id",UUID.class),rs.getObject("owner_id",UUID.class)))
                .optional().orElseThrow(()->new ApiException("ACTION_NOT_FOUND","整改措施不存在",HttpStatus.NOT_FOUND)); requireVisibleForUpdate(actor,row.incidentId());
        String result=upper(decision); if(!Set.of("ACCEPTED","RETURNED").contains(result)||("RETURNED".equals(result)&&(comment==null||comment.isBlank()))) invalid();
        int updated=jdbc.sql("""
                UPDATE incident_corrective_action SET status=:status,accepted_by=:actorId,acceptance_result=:result,
                  acceptance_comment=:comment,accepted_at=CURRENT_TIMESTAMP,version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND version=:version AND status='PENDING_ACCEPTANCE'
                """).param("status",result).param("actorId",actor.userId()).param("result",result).param("comment",clean(comment))
                .param("id",actionId).param("version",version).update(); if(updated!=1) conflict();
        jdbc.sql("UPDATE equipment_incident SET status=:status,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("status","RETURNED".equals(result)?"CORRECTING":"PENDING_ACCEPTANCE").param("id",row.incidentId()).update();
        event(actor,row.incidentId(),"CORRECTIVE_ACTION_"+result,"INCIDENT_CORRECTIVE_ACTION",actionId,comment,requestId); return get(actor,row.incidentId());
    }

    @Transactional
    public DossierView archive(CurrentUser actor, UUID incidentId, long version, String requestId) {
        requireSupervisor(actor); IncidentBase base=requireVisibleForUpdate(actor,incidentId); if(base.version()!=version) conflict();
        ArchiveReadiness readiness=readiness(incidentId); if(!readiness.ready()) throw new ApiException("INCIDENT_ARCHIVE_INCOMPLETE",String.join("；",readiness.missingItems()),HttpStatus.CONFLICT);
        String check=json(readiness); int updated=jdbc.sql("""
                UPDATE equipment_incident SET status='ARCHIVED',archive_check_result=CAST(:check AS jsonb),archived_at=CURRENT_TIMESTAMP,
                  archive_version=archive_version+1,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version
                """).param("check",check).param("id",incidentId).param("version",version).update(); if(updated!=1) conflict();
        event(actor,incidentId,"DOSSIER_ARCHIVED","EQUIPMENT_INCIDENT",incidentId,readiness,requestId);
        audit.record(actor.userId(),"INCIDENT_DOSSIER_ARCHIVE","EQUIPMENT_INCIDENT",incidentId,check,requestId); return get(actor,incidentId);
    }

    @Transactional
    public DossierView relate(CurrentUser actor,UUID incidentId,UUID relatedIncidentId,String relationType,String requestId){
        requireSupervisor(actor);requireVisibleForUpdate(actor,incidentId);requireVisible(actor,relatedIncidentId);String type=upper(relationType);
        if(incidentId.equals(relatedIncidentId)||!Set.of("SIMILAR","REPEAT","UPSTREAM","DOWNSTREAM").contains(type))invalid();
        UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO incident_relation(id,incident_id,related_incident_id,relation_type,confirmed_by) VALUES (:id,:incidentId,:relatedId,:type,:actorId) ON CONFLICT (incident_id,related_incident_id,relation_type) DO NOTHING").param("id",id).param("incidentId",incidentId).param("relatedId",relatedIncidentId).param("type",type).param("actorId",actor.userId()).update();
        event(actor,incidentId,"INCIDENT_RELATION_CONFIRMED","INCIDENT_RELATION",id,new RelationInput(relatedIncidentId,type),requestId);return get(actor,incidentId);
    }

    @Transactional
    public DossierView reopen(CurrentUser actor,UUID incidentId,long version,String reason,String requestId){
        requireSupervisor(actor);IncidentBase base=requireVisibleForUpdate(actor,incidentId);if(base.version()!=version||!"ARCHIVED".equals(base.status()))state();
        int updated=jdbc.sql("UPDATE equipment_incident SET status='CORRECTING',reopened_at=CURRENT_TIMESTAMP,reopened_by=:actorId,reopen_reason=:reason,archived_at=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status='ARCHIVED'").param("actorId",actor.userId()).param("reason",required(reason,1000)).param("id",incidentId).param("version",version).update();if(updated!=1)conflict();event(actor,incidentId,"DOSSIER_REOPENED","EQUIPMENT_INCIDENT",incidentId,reason,requestId);return get(actor,incidentId);
    }

    private ArchiveReadiness readiness(UUID incidentId) {
        List<String> missing=new java.util.ArrayList<>();
        long pendingStatements=jdbc.sql("SELECT COUNT(*) FROM incident_statement WHERE incident_id=:id AND status<>'APPROVED'").param("id",incidentId).query(Long.class).single();
        long confirmedInvestigation=jdbc.sql("SELECT COUNT(*) FROM incident_investigation WHERE incident_id=:id AND status='CONFIRMED'").param("id",incidentId).query(Long.class).single();
        long openActions=jdbc.sql("SELECT COUNT(*) FROM incident_corrective_action WHERE incident_id=:id AND status NOT IN ('ACCEPTED','CANCELLED')").param("id",incidentId).query(Long.class).single();
        long totalActions=jdbc.sql("SELECT COUNT(*) FROM incident_corrective_action WHERE incident_id=:id").param("id",incidentId).query(Long.class).single();
        boolean performanceRequired=jdbc.sql("SELECT performance_required FROM equipment_incident WHERE id=:id").param("id",incidentId).query(Boolean.class).single();
        long performanceComplete=jdbc.sql("SELECT COUNT(*) FROM performance_case WHERE incident_id=:id AND status IN ('EFFECTIVE','APPROVED','CLOSED')").param("id",incidentId).query(Long.class).single();
        if(pendingStatements>0)missing.add("相关人员说明尚未全部审核通过"); if(confirmedInvestigation==0)missing.add("调查结论尚未确认");
        if(totalActions==0)missing.add("尚未建立整改措施"); if(openActions>0)missing.add("整改措施尚未全部验收");
        if(performanceRequired&&performanceComplete==0)missing.add("绩效处理尚未完成"); return new ArchiveReadiness(missing.isEmpty(),missing);
    }

    private List<ActionView> actions(UUID incidentId){return jdbc.sql("""
            SELECT a.id,a.action_type,a.content,a.owner_id,u.display_name,a.due_at,a.status,a.completion_note,a.completed_at,
              a.accepted_by,acceptor.display_name,a.acceptance_result,a.acceptance_comment,a.accepted_at,a.version,a.created_at
            FROM incident_corrective_action a JOIN app_user u ON u.id=a.owner_id LEFT JOIN app_user acceptor ON acceptor.id=a.accepted_by
            WHERE a.incident_id=:incidentId ORDER BY a.created_at,a.id
            """).param("incidentId",incidentId).query((rs,n)->new ActionView(rs.getObject("id",UUID.class),rs.getString("action_type"),
            rs.getString("content"),rs.getObject("owner_id",UUID.class),rs.getString(5),rs.getObject("due_at",OffsetDateTime.class),
            rs.getString("status"),rs.getString("completion_note"),rs.getObject("completed_at",OffsetDateTime.class),
            rs.getObject("accepted_by",UUID.class),rs.getString(11),rs.getString("acceptance_result"),rs.getString("acceptance_comment"),
            rs.getObject("accepted_at",OffsetDateTime.class),rs.getLong("version"),rs.getObject("created_at",OffsetDateTime.class))).list();}

    private IncidentBase requireVisible(CurrentUser actor,UUID id){IncidentBase base=jdbc.sql("SELECT id,incident_no,status,team_id,severity,category_code,impact_level,downtime_minutes,impact_description,performance_required,version,created_by FROM equipment_incident WHERE id=:id")
            .param("id",id).query((rs,n)->new IncidentBase(rs.getObject("id",UUID.class),rs.getString("incident_no"),rs.getString("status"),rs.getObject("team_id",UUID.class),rs.getString("severity"),rs.getString("category_code"),rs.getString("impact_level"),rs.getInt("downtime_minutes"),rs.getString("impact_description"),rs.getBoolean("performance_required"),rs.getLong("version"),rs.getObject("created_by",UUID.class))).optional().orElseThrow(()->new ApiException("INCIDENT_NOT_FOUND","异常档案不存在",HttpStatus.NOT_FOUND));
        long involved=jdbc.sql("SELECT COUNT(*) FROM incident_statement WHERE incident_id=:id AND responsible_user_id=:actorId").param("id",id).param("actorId",actor.userId()).query(Long.class).single(); if(!base.createdBy().equals(actor.userId())&&involved==0&&!dataScope.canAccessOrg(actor,base.teamId()))forbidden(); return base;}
    private IncidentBase requireVisibleForUpdate(CurrentUser actor,UUID id){jdbc.sql("SELECT id FROM equipment_incident WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().orElseThrow(()->new ApiException("INCIDENT_NOT_FOUND","异常档案不存在",HttpStatus.NOT_FOUND));return requireVisible(actor,id);}
    private void requireManager(CurrentUser actor){if(!actor.administrator()&&actor.roles().stream().noneMatch(Set.of(BusinessRoles.ASSISTANT_ENGINEER,BusinessRoles.SUPERVISOR,BusinessRoles.DEPARTMENT_MANAGER)::contains))forbidden();}
    private void requireSupervisor(CurrentUser actor){if(!actor.administrator()&&!actor.roles().contains(BusinessRoles.SUPERVISOR)&&!actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER))forbidden();}
    private void event(CurrentUser actor,UUID incidentId,String type,String objectType,UUID objectId,Object detail,String requestId){jdbc.sql("INSERT INTO incident_archive_event(id,incident_id,event_type,actor_id,actor_roles,actor_org_id,object_type,object_id,detail,request_id) VALUES (:id,:incidentId,:type,:actorId,:roles,:orgId,:objectType,:objectId,CAST(:detail AS jsonb),:requestId)")
            .param("id",UUID.randomUUID()).param("incidentId",incidentId).param("type",type).param("actorId",actor.userId()).param("roles",String.join(",",actor.roles())).param("orgId",actor.orgUnitId()).param("objectType",objectType).param("objectId",objectId).param("detail",json(detail)).param("requestId",requestId).update();}
    private String json(Object value){try{return value instanceof String s&&s.startsWith("{")?s:mapper.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalStateException(ex);}}
    private String required(String value,int max){if(value==null||value.isBlank()||value.length()>max)invalid();return value.trim();}
    private String clean(String value){return value==null||value.isBlank()?null:value.trim();} private String upper(String value){return value==null?"":value.trim().toUpperCase();}
    private void forbidden(){throw new ApiException("FORBIDDEN","当前角色或数据范围无权执行此操作",HttpStatus.FORBIDDEN);} private void invalid(){throw new ApiException("INVALID_DOSSIER","异常档案内容不完整或不合法",HttpStatus.BAD_REQUEST);} private void state(){throw new ApiException("INVALID_DOSSIER_STATE","当前档案状态不允许执行此操作",HttpStatus.CONFLICT);} private void conflict(){throw new ApiException("VERSION_CONFLICT","档案已被其他人处理，请刷新后重试",HttpStatus.CONFLICT);}

    private record IncidentBase(UUID id,String incidentNo,String status,UUID teamId,String severity,String categoryCode,String impactLevel,int downtimeMinutes,String impactDescription,boolean performanceRequired,long version,UUID createdBy){}
    private record ActionOwner(UUID incidentId,UUID ownerId){}
    public record MetadataInput(String severity,String categoryCode,String impactLevel,int downtimeMinutes,String impactDescription,boolean performanceRequired){}
    public record EvidenceInput(UUID attachmentId,String evidenceType,String description){}
    public record InvestigationInput(String mode,String directCause,String rootCause,String rootCauseCategory,Object fiveWhys,String conclusion){}
    public record ResponsibilityInput(UUID responsibleUserId,UUID responsibleOrgId,String responsibilityType,Integer responsibilityPercent,String basis,String proposedAction){}
    public record CorrectiveActionInput(String actionType,String content,UUID ownerId,OffsetDateTime dueAt){}
    public record RelationInput(UUID relatedIncidentId,String relationType){}
    public record ArchiveReadiness(boolean ready,List<String> missingItems){}
    public record InvestigationView(UUID id,String mode,UUID leadUserId,String directCause,String rootCause,String rootCauseCategory,String fiveWhys,String conclusion,String status,UUID confirmedBy,OffsetDateTime confirmedAt,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
    public record EvidenceView(UUID id,UUID attachmentId,String evidenceType,String description,UUID uploadedBy,String uploaderName,String mimeType,long sizeBytes,String status,OffsetDateTime createdAt){}
    public record ResponsibilityView(UUID id,UUID responsibleUserId,String responsibleUserName,UUID responsibleOrgId,String responsibleOrgName,String responsibilityType,Integer responsibilityPercent,String basis,String proposedAction,String status,long version,OffsetDateTime createdAt){}
    public record ActionView(UUID id,String actionType,String content,UUID ownerId,String ownerName,OffsetDateTime dueAt,String status,String completionNote,OffsetDateTime completedAt,UUID acceptedBy,String acceptorName,String acceptanceResult,String acceptanceComment,OffsetDateTime acceptedAt,long version,OffsetDateTime createdAt){}
    public record EventView(UUID id,String eventType,UUID actorId,String actorName,String objectType,UUID objectId,String detail,String requestId,OffsetDateTime createdAt){}
    public record DossierView(UUID id,String incidentNo,String status,UUID teamId,String severity,String categoryCode,String impactLevel,int downtimeMinutes,String impactDescription,boolean performanceRequired,long version,InvestigationView investigation,List<EvidenceView> evidence,List<ResponsibilityView> responsibilities,List<ActionView> actions,List<EventView> events,ArchiveReadiness archiveReadiness){}
    public record DossierSummary(UUID id,String incidentNo,String status,UUID teamId,String teamName,String severity,String categoryCode,String impactLevel,int downtimeMinutes,OffsetDateTime occurredAt,long openActions){}
}
