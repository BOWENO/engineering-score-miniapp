package com.acme.performance.performance.service;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.common.service.BusinessDeadlineService;
import com.acme.performance.notification.service.NotificationService;
import com.acme.performance.organization.model.BusinessRoles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PerformanceCaseService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final BusinessDeadlineService deadlines;
    private final AuditLogService audit;

    public PerformanceCaseService(JdbcClient jdbc, NotificationService notifications,
                                  BusinessDeadlineService deadlines, AuditLogService audit) {
        this.jdbc=jdbc; this.notifications=notifications; this.deadlines=deadlines; this.audit=audit;
    }

    @Transactional
    public CaseView applyBonus(CurrentUser actor, OffsetDateTime occurredAt, String description,
                               List<UUID> attachmentIds, String requestId) {
        requireScored(actor); validateOccurredThisMonth(occurredAt); requireDescription(description);
        validateAttachments(actor,attachmentIds);
        String stage=actor.roles().contains(BusinessRoles.TECHNICIAN)?"ASSISTANT":"SUPERVISOR";
        String status="ASSISTANT".equals(stage)?"PENDING_ASSISTANT":"PENDING_SUPERVISOR";
        OffsetDateTime due=deadlines.addHours(OffsetDateTime.now(ZONE),24,actor.userId());
        UUID id=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO performance_case(id,case_type,target_user_id,initiated_by,occurred_at,description,status,current_stage,due_at)
                VALUES (:id,'BONUS',:targetId,:actorId,:occurredAt,:description,:status,:stage,:dueAt)
                """).param("id",id).param("targetId",actor.userId()).param("actorId",actor.userId())
                .param("occurredAt",occurredAt).param("description",description.trim()).param("status",status)
                .param("stage",stage).param("dueAt",due).update();
        linkAttachments(id,attachmentIds); action(id,actor.userId(),"SUBMIT",stage,null,null,"NEW",status);
        notifyNextReviewer(id,actor,stage,"新的加分申请待审核");
        audit.record(actor.userId(),"BONUS_APPLY","PERFORMANCE_CASE",id,"{}",requestId);
        return get(id);
    }

    @Transactional
    public CaseView createDeduction(CurrentUser actor, DeductionInput input, String requestId) {
        requireDescription(input.description());
        if(input.occurredAt()==null||input.targetUserId()==null||!Set.of("BASE_DEDUCTION","SPECIAL_DEDUCTION").contains(input.caseType())) invalid();
        validateAttachments(actor,input.attachmentIds()); requireDeductionTarget(actor,input.targetUserId());
        if(input.incidentId()!=null){long related=jdbc.sql("SELECT COUNT(*) FROM incident_statement WHERE incident_id=:incidentId AND responsible_user_id=:targetId").param("incidentId",input.incidentId()).param("targetId",input.targetUserId()).query(Long.class).single();if(related==0)throw new ApiException("INVALID_INCIDENT_LINK","绩效对象不是该异常档案的相关责任人",HttpStatus.BAD_REQUEST);}
        boolean supervisor=actor.roles().contains(BusinessRoles.SUPERVISOR);
        if(supervisor||clean(input.ruleCode())!=null) requireRule(input.ruleCode(),input.caseType(),input.occurredAt());
        if(input.score()==null||input.score()<=0) throw new ApiException("INVALID_SCORE","扣分值必须为正整数",HttpStatus.BAD_REQUEST);
        UUID id=UUID.randomUUID(); OffsetDateTime now=OffsetDateTime.now(ZONE);
        String status=supervisor?"EFFECTIVE":"PENDING_SUPERVISOR";
        OffsetDateTime due=supervisor?null:deadlines.addHours(now,24,input.targetUserId());
        Integer approved=supervisor?input.score():null;
        jdbc.sql("""
                INSERT INTO performance_case(id,case_type,target_user_id,initiated_by,occurred_at,description,rule_code,
                  suggested_score,approved_score,status,current_stage,direct_by_supervisor,original_supervisor_id,due_at,incident_id)
                VALUES (:id,:type,:targetId,:actorId,:occurredAt,:description,:ruleCode,:suggested,:approved,:status,
                  :stage,:direct,:supervisorId,:dueAt,:incidentId)
                """).param("id",id).param("type",input.caseType()).param("targetId",input.targetUserId())
                .param("actorId",actor.userId()).param("occurredAt",input.occurredAt()).param("description",input.description().trim())
                .param("ruleCode",clean(input.ruleCode())).param("suggested",input.score()).param("approved",approved)
                .param("status",status).param("stage",supervisor?null:"SUPERVISOR").param("direct",supervisor)
                .param("supervisorId",supervisor?actor.userId():null).param("dueAt",due).param("incidentId",input.incidentId()).update();
        linkAttachments(id,input.attachmentIds()); action(id,actor.userId(),supervisor?"DIRECT_EFFECTIVE":"SUBMIT",
                supervisor?null:"SUPERVISOR",null,supervisor?approved:null,"NEW",status);
        if(supervisor) makeEffective(id,actor,input.score(),input.ruleCode());
        else notifySupervisors(id,"新的扣分建议待审核");
        audit.record(actor.userId(),input.caseType()+"_CREATE","PERFORMANCE_CASE",id,"{}",requestId);
        if(input.incidentId()!=null)jdbc.sql("INSERT INTO incident_archive_event(id,incident_id,event_type,actor_id,actor_roles,actor_org_id,object_type,object_id,request_id) VALUES (:id,:incidentId,'PERFORMANCE_CASE_CREATED',:actorId,:roles,:orgId,'PERFORMANCE_CASE',:caseId,:requestId)").param("id",UUID.randomUUID()).param("incidentId",input.incidentId()).param("actorId",actor.userId()).param("roles",String.join(",",actor.roles())).param("orgId",actor.orgUnitId()).param("caseId",id).param("requestId",requestId).update();
        return get(id);
    }

    @Transactional
    public CaseView createAdmonition(CurrentUser actor, UUID targetUserId, OffsetDateTime occurredAt,
                                     String description, String requestId) {
        requireSupervisor(actor); requireDescription(description); requireScoredTarget(targetUserId); requireScope(actor,targetUserId);
        UUID id=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO performance_case(id,case_type,target_user_id,initiated_by,occurred_at,description,status,
                  direct_by_supervisor,original_supervisor_id)
                VALUES (:id,'ADMONITION',:targetId,:actorId,:occurredAt,:description,'EFFECTIVE',TRUE,:actorId)
                """).param("id",id).param("targetId",targetUserId).param("actorId",actor.userId())
                .param("occurredAt",occurredAt).param("description",description.trim()).update();
        action(id,actor.userId(),"ADMONISH",null,description,null,"NEW","EFFECTIVE");
        notifications.create(targetUserId,"ADMONITION","收到一条劝诫记录",description.trim(),"PERFORMANCE_CASE",id,false);
        audit.record(actor.userId(),"ADMONITION_CREATE","PERFORMANCE_CASE",id,"{}",requestId); return get(id);
    }

    @Transactional(readOnly=true)
    public List<CaseView> mine(CurrentUser actor){return query(" WHERE p.target_user_id=:userId ",Map.of("userId",actor.userId()));}

    @Transactional(readOnly=true)
    public List<CaseView> records(CurrentUser actor,String period){
        if(actor.roles().stream().noneMatch(Set.of(BusinessRoles.ASSISTANT_ENGINEER,BusinessRoles.SUPERVISOR,BusinessRoles.DEPARTMENT_MANAGER)::contains))forbidden();
        java.time.YearMonth month;try{month=java.time.YearMonth.parse(period);}catch(Exception ex){throw new ApiException("INVALID_PERIOD","请选择有效月份",HttpStatus.BAD_REQUEST);}
        return jdbc.sql(caseSelect()+" WHERE p.occurred_at>=:from AND p.occurred_at<:to ORDER BY p.occurred_at DESC,p.id").param("from",month.atDay(1).atStartOfDay(ZONE).toOffsetDateTime()).param("to",month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime()).query(this::mapCase).list().stream().filter(item->canAccess(actor,item.targetUserId())).toList();
    }

    @Transactional(readOnly=true)
    public List<CaseView> pending(CurrentUser actor){
        if(actor.roles().contains(BusinessRoles.ASSISTANT_ENGINEER)){
            return query(" WHERE p.status='PENDING_ASSISTANT' AND target.org_unit_id=:teamId ",Map.of("teamId",actor.orgUnitId())).stream().filter(item->canAccess(actor,item.targetUserId())).toList();
        }
        if(actor.roles().contains(BusinessRoles.SUPERVISOR)){
            return query(" WHERE p.status='PENDING_SUPERVISOR' ",Map.of()).stream().filter(item->canAccess(actor,item.targetUserId())).toList();
        }
        if(actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER)){
            return query(" WHERE p.status IN ('PENDING_SUPERVISOR','PENDING_ASSISTANT') ",Map.of()).stream().filter(item->canAccess(actor,item.targetUserId())).toList();
        }
        return List.of();
    }

    @Transactional
    public CaseView review(CurrentUser actor, UUID caseId, long version, String decision, Integer score,
                           String ruleCode, String comment, String requestId) {
        CaseView item=getForUpdate(caseId); requireScope(actor,item.targetUserId()); String action=decision==null?"":decision.toUpperCase();
        if(item.version()!=version) conflict();
        if("PENDING_ASSISTANT".equals(item.status())) return assistantReview(actor,item,action,comment,requestId);
        if("PENDING_SUPERVISOR".equals(item.status())) return supervisorReview(actor,item,action,score,ruleCode,comment,requestId);
        throw new ApiException("CASE_ALREADY_HANDLED","该事项已经被处理",HttpStatus.CONFLICT);
    }

    private CaseView assistantReview(CurrentUser actor,CaseView item,String decision,String comment,String requestId){
        if(!actor.roles().contains(BusinessRoles.ASSISTANT_ENGINEER)||!actor.orgUnitId().equals(item.targetTeamId())) forbidden();
        String status=switch(decision){case "APPROVE"->"PENDING_SUPERVISOR";case "RETURN"->"RETURNED";case "REJECT"->"REJECTED";default->throw invalidDecision();};
        String stage="APPROVE".equals(decision)?"SUPERVISOR":null;
        updateState(item.id(),item.version(),status,stage,"APPROVE".equals(decision)?deadlines.addHours(OffsetDateTime.now(ZONE),24,item.targetUserId()):null);
        action(item.id(),actor.userId(),decision,"ASSISTANT",comment,null,item.status(),status);
        if("APPROVE".equals(decision)) notifySupervisors(item.id(),"加分申请已通过初审");
        else notifications.create(item.targetUserId(),"BONUS_REVIEW","加分申请"+("RETURN".equals(decision)?"被打回":"被否决"),
                clean(comment)==null?"请查看审核结果":comment.trim(),"PERFORMANCE_CASE",item.id(),false);
        audit.record(actor.userId(),"BONUS_ASSISTANT_"+decision,"PERFORMANCE_CASE",item.id(),"{}",requestId); return get(item.id());
    }

    private CaseView supervisorReview(CurrentUser actor,CaseView item,String decision,Integer score,String ruleCode,String comment,String requestId){
        requireSupervisor(actor); String status;
        if("APPROVE".equals(decision)){
            if(score==null||score<=0) throw new ApiException("INVALID_SCORE","通过时必须填写正整数分值",HttpStatus.BAD_REQUEST);
            if(clean(ruleCode)==null) throw new ApiException("RULE_REQUIRED","通过时必须归入积分规则项目",HttpStatus.BAD_REQUEST);
            requireRule(ruleCode,item.caseType(),item.occurredAt());
            status="EFFECTIVE";
            int updated=jdbc.sql("""
                    UPDATE performance_case SET approved_score=:score,rule_code=:ruleCode,status='EFFECTIVE',current_stage=NULL,
                      original_supervisor_id=:actorId,version=version+1,updated_at=CURRENT_TIMESTAMP
                    WHERE id=:id AND version=:version
                    """).param("score",score).param("ruleCode",ruleCode.trim()).param("actorId",actor.userId())
                    .param("id",item.id()).param("version",item.version()).update(); if(updated!=1)conflict();
            action(item.id(),actor.userId(),decision,"SUPERVISOR",comment,score,item.status(),status);
            makeEffective(item.id(),actor,score,ruleCode);
        } else {
            status=switch(decision){case "RETURN"->"RETURNED";case "REJECT"->"REJECTED";default->throw invalidDecision();};
            updateState(item.id(),item.version(),status,null,null);
            action(item.id(),actor.userId(),decision,"SUPERVISOR",comment,null,item.status(),status);
            notifications.create(item.targetUserId(),"PERFORMANCE_REVIEW","绩效事项"+("RETURN".equals(decision)?"被打回":"被否决"),
                    clean(comment)==null?"请查看审核结果":comment.trim(),"PERFORMANCE_CASE",item.id(),false);
        }
        audit.record(actor.userId(),"PERFORMANCE_SUPERVISOR_"+decision,"PERFORMANCE_CASE",item.id(),"{}",requestId); return get(item.id());
    }

    @Transactional
    public CaseView resubmitBonus(CurrentUser actor,UUID caseId,long version,String description,List<UUID> attachments,String requestId){
        CaseView item=getForUpdate(caseId); if(!item.targetUserId().equals(actor.userId())||!"BONUS".equals(item.caseType())||!"RETURNED".equals(item.status()))forbidden();
        if(item.version()!=version)conflict(); requireDescription(description); validateAttachments(actor,attachments);
        jdbc.sql("DELETE FROM performance_case_attachment WHERE case_id=:id").param("id",caseId).update(); linkAttachments(caseId,attachments);
        String stage=actor.roles().contains(BusinessRoles.TECHNICIAN)?"ASSISTANT":"SUPERVISOR"; String status="ASSISTANT".equals(stage)?"PENDING_ASSISTANT":"PENDING_SUPERVISOR";
        int updated=jdbc.sql("UPDATE performance_case SET description=:description,status=:status,current_stage=:stage,due_at=:dueAt,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version")
                .param("description",description.trim()).param("status",status).param("stage",stage)
                .param("dueAt",deadlines.addHours(OffsetDateTime.now(ZONE),24,actor.userId())).param("id",caseId).param("version",version).update();if(updated!=1)conflict();
        action(caseId,actor.userId(),"RESUBMIT",stage,null,null,item.status(),status);notifyNextReviewer(caseId,actor,stage,"修改后的加分申请待审核");
        audit.record(actor.userId(),"BONUS_RESUBMIT","PERFORMANCE_CASE",caseId,"{}",requestId);return get(caseId);
    }

    @Transactional
    public AppealView appeal(CurrentUser actor,UUID caseId,String description,String requestId){
        CaseView item=getForUpdate(caseId); requireDescription(description);
        if(!item.targetUserId().equals(actor.userId())||!Set.of("BASE_DEDUCTION","SPECIAL_DEDUCTION").contains(item.caseType())||!"EFFECTIVE".equals(item.status()))forbidden();
        if(item.appealDeadlineAt()==null||deadlines.isPast(item.appealDeadlineAt()))throw new ApiException("APPEAL_EXPIRED","扣分生效后24小时内才可申诉",HttpStatus.CONFLICT);
        UUID id=UUID.randomUUID(); OffsetDateTime due=deadlines.addHours(OffsetDateTime.now(ZONE),24,actor.userId());
        try{jdbc.sql("INSERT INTO performance_appeal(id,case_id,applicant_id,description,status,due_at) VALUES (:id,:caseId,:applicantId,:description,'PENDING',:dueAt)")
                .param("id",id).param("caseId",caseId).param("applicantId",actor.userId()).param("description",description.trim()).param("dueAt",due).update();}
        catch(org.springframework.dao.DataIntegrityViolationException ex){throw new ApiException("APPEAL_ALREADY_EXISTS","每条扣分只能申诉一次",HttpStatus.CONFLICT);}
        notifyEligibleSupervisors(item,"新的扣分申诉待表决");audit.record(actor.userId(),"DEDUCTION_APPEAL","PERFORMANCE_APPEAL",id,"{}",requestId);return appealView(id);
    }

    @Transactional
    public AppealView vote(CurrentUser actor,UUID appealId,String decision,String comment,String requestId){
        requireSupervisor(actor); AppealView appeal=appealViewForUpdate(appealId); if(!"PENDING".equals(appeal.status()))throw new ApiException("APPEAL_ALREADY_HANDLED","申诉已经结束",HttpStatus.CONFLICT);
        CaseView item=get(appeal.caseId()); requireScope(actor,item.targetUserId()); if(actor.userId().equals(item.originalSupervisorId()))throw new ApiException("APPEAL_RECUSAL_REQUIRED","原扣分主管必须回避申诉表决",HttpStatus.FORBIDDEN);
        String normalized=decision==null?"":decision.toUpperCase();if(!Set.of("APPROVE","REJECT").contains(normalized))throw invalidDecision();
        try{jdbc.sql("INSERT INTO performance_appeal_vote(id,appeal_id,supervisor_id,decision,comment) VALUES (:id,:appealId,:actorId,:decision,:comment)")
                .param("id",UUID.randomUUID()).param("appealId",appealId).param("actorId",actor.userId()).param("decision",normalized).param("comment",clean(comment)).update();}
        catch(org.springframework.dao.DataIntegrityViolationException ex){throw new ApiException("APPEAL_ALREADY_VOTED","每位主管只能表决一次",HttpStatus.CONFLICT);}
        if("REJECT".equals(normalized))finishAppeal(appeal,item,"REJECTED");
        else {Long approvals=jdbc.sql("SELECT COUNT(*) FROM performance_appeal_vote WHERE appeal_id=:id AND decision='APPROVE'").param("id",appealId).query(Long.class).single();if(approvals>=2)finishAppeal(appeal,item,"ACCEPTED");}
        audit.record(actor.userId(),"APPEAL_VOTE_"+normalized,"PERFORMANCE_APPEAL",appealId,"{}",requestId);return appealView(appealId);
    }

    @Transactional(readOnly=true)
    public List<AppealView> pendingAppeals(CurrentUser actor){
        if(!actor.roles().contains(BusinessRoles.SUPERVISOR)&&!actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER))return List.of();
        return jdbc.sql(appealSelect()+" WHERE a.status='PENDING' ORDER BY a.due_at").query(this::mapAppeal).list().stream().filter(item->canAccess(actor,item.applicantId())).toList();
    }

    private void finishAppeal(AppealView appeal,CaseView item,String result){
        jdbc.sql("UPDATE performance_appeal SET status=:status,decided_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id AND status='PENDING'")
                .param("status",result).param("id",appeal.id()).update();
        if("ACCEPTED".equals(result)){
            int amount=item.effectiveScore()==null?0:item.effectiveScore(); LocalDate date=item.occurredAt().atZoneSameInstant(ZONE).toLocalDate();
            jdbc.sql("INSERT INTO score_event(id,user_id,rule_code,biz_date,original_score,actual_score,source,source_id) VALUES (:id,:userId,'APPEAL_REVERSAL',:date,:score,:score,'PERFORMANCE_APPEAL',:sourceId)")
                    .param("id",UUID.randomUUID()).param("userId",item.targetUserId()).param("date",date).param("score",BigDecimal.valueOf(amount)).param("sourceId",appeal.id()).update();
            updateSummary(item.targetUserId(),date,0,-amount,amount);jdbc.sql("UPDATE performance_case SET status='REVERSED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",item.id()).update();
        }
        notifications.create(item.targetUserId(),"APPEAL_RESULT","扣分申诉"+("ACCEPTED".equals(result)?"成功":"被驳回"),"请查看完整表决结果","PERFORMANCE_APPEAL",appeal.id(),false);
    }

    private void makeEffective(UUID caseId,CurrentUser supervisor,int approvedScore,String ruleCode){
        ruleCode=clean(ruleCode);
        CaseView item=get(caseId);
        // Serialize each person's capped ledger calculation, including first entry of a month.
        jdbc.sql("SELECT id FROM app_user WHERE id=:id FOR UPDATE").param("id",item.targetUserId()).query(UUID.class).single();
        int effective=effectiveAmount(item,approvedScore,ruleCode); LocalDate date=item.occurredAt().atZoneSameInstant(ZONE).toLocalDate();
        boolean bonus="BONUS".equals(item.caseType()); BigDecimal signed=BigDecimal.valueOf(bonus?effective:-effective);
        jdbc.sql("INSERT INTO score_event(id,user_id,rule_code,biz_date,original_score,actual_score,cap_reason,source,source_id) VALUES (:id,:userId,:ruleCode,:date,:original,:actual,:capReason,'PERFORMANCE_CASE',:sourceId)")
                .param("id",UUID.randomUUID()).param("userId",item.targetUserId()).param("ruleCode",ruleCode==null?item.caseType():ruleCode)
                .param("date",date).param("original",BigDecimal.valueOf(bonus?approvedScore:-approvedScore)).param("actual",signed)
                .param("capReason",effective<approvedScore?"MONTHLY_CAP":null).param("sourceId",caseId).update();
        OffsetDateTime appealDeadline=bonus?null:deadlines.addHours(OffsetDateTime.now(ZONE),24,item.targetUserId());
        jdbc.sql("UPDATE performance_case SET effective_score=:effective,appeal_deadline_at=:appealDeadline,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("effective",effective).param("appealDeadline",appealDeadline).param("id",caseId).update();
        updateSummary(item.targetUserId(),date,bonus?effective:0,bonus?0:effective,bonus?effective:-effective);
        notifications.create(item.targetUserId(),bonus?"BONUS_APPROVED":"DEDUCTION_EFFECTIVE",bonus?"加分已生效":"扣分已生效",
                (bonus?"本次实际计入 ":"本次扣除 ")+effective+" 分","PERFORMANCE_CASE",caseId,false);
    }

    private int effectiveAmount(CaseView item,int approved,String ruleCode){
        LocalDate date=item.occurredAt().atZoneSameInstant(ZONE).toLocalDate(); String period=date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        if("BONUS".equals(item.caseType())){
            BigDecimal used=jdbc.sql("SELECT COALESCE(bonus,0) FROM score_summary WHERE user_id=:userId AND period=:period")
                    .param("userId",item.targetUserId()).param("period",period).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
            return Math.max(0,Math.min(approved,20-used.intValue()));
        }
        Integer cap=jdbc.sql("""
                SELECT NULLIF(r.cap_policy->>'monthlyCap','')::INTEGER FROM score_rule r
                WHERE r.code=:code AND :date BETWEEN r.effective_from AND COALESCE(r.effective_to,:date)
                  AND r.rule_version_id=(SELECT id FROM rule_version WHERE status IN ('PUBLISHED','RETIRED') AND effective_at<=:at ORDER BY effective_at DESC,created_at DESC LIMIT 1)
                ORDER BY r.effective_from DESC LIMIT 1
                """).param("code",ruleCode==null?"":ruleCode).param("date",date).param("at",item.occurredAt()).query(Integer.class).optional().orElse(null);
        if(cap==null)return approved;
        BigDecimal used=jdbc.sql("""
                SELECT COALESCE(SUM(ABS(actual_score)),0) FROM score_event WHERE user_id=:userId AND rule_code=:code
                  AND biz_date>=:monthStart AND biz_date<:nextMonth AND actual_score<0
                """).param("userId",item.targetUserId()).param("code",ruleCode).param("monthStart",date.withDayOfMonth(1))
                .param("nextMonth",date.withDayOfMonth(1).plusMonths(1)).query(BigDecimal.class).single();
        return Math.max(0,Math.min(approved,cap-used.intValue()));
    }

    private void updateSummary(UUID userId,LocalDate date,int bonusDelta,int penaltyDelta,int totalDelta){
        String period=date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        jdbc.sql("""
                INSERT INTO score_summary(user_id,period,bonus,penalty,total) VALUES (:userId,:period,:bonus,:penalty,:total)
                ON CONFLICT (user_id,period) DO UPDATE SET bonus=score_summary.bonus+:bonus,
                  penalty=GREATEST(0,score_summary.penalty+:penalty),total=score_summary.total+:total,rebuilt_at=CURRENT_TIMESTAMP
                """).param("userId",userId).param("period",period).param("bonus",BigDecimal.valueOf(bonusDelta))
                .param("penalty",BigDecimal.valueOf(penaltyDelta)).param("total",BigDecimal.valueOf(totalDelta)).update();
    }

    private void requireDeductionTarget(CurrentUser actor,UUID targetId){
        requireScoredTarget(targetId); requireScope(actor,targetId); if(actor.roles().contains(BusinessRoles.SUPERVISOR))return;
        if(!actor.roles().contains(BusinessRoles.ASSISTANT_ENGINEER))forbidden();
        Target target=target(targetId);if(!target.teamId().equals(actor.orgUnitId())||!target.roles().contains(BusinessRoles.TECHNICIAN))forbidden();
    }
    private void requireScoredTarget(UUID targetId){if(target(targetId).roles().stream().noneMatch(BusinessRoles.SCORED::contains))throw new ApiException("INVALID_TARGET","只能对技术员或助理工程师建立绩效事项",HttpStatus.BAD_REQUEST);}
    private boolean canAccess(CurrentUser actor,UUID targetId){return new com.acme.performance.organization.service.DataScopeService(jdbc).canAccessUser(actor,targetId);}
    private void requireScope(CurrentUser actor,UUID targetId){if(!canAccess(actor,targetId))forbidden();}
    private void requireRule(String code,String caseType,OffsetDateTime occurredAt){
        if(clean(code)==null)throw new ApiException("RULE_REQUIRED","请选择适用的积分规则",HttpStatus.BAD_REQUEST);
        Long count=jdbc.sql("SELECT COUNT(*) FROM score_rule r JOIN rule_version v ON v.id=r.rule_version_id WHERE r.code=:code AND v.status IN ('PUBLISHED','RETIRED') AND r.effective_from<=:date AND (r.effective_to IS NULL OR r.effective_to>=:date) AND v.id=(SELECT id FROM rule_version WHERE status IN ('PUBLISHED','RETIRED') AND effective_at<=:at ORDER BY effective_at DESC,created_at DESC LIMIT 1) AND r.type=:type")
            .param("code",code.trim()).param("date",occurredAt.atZoneSameInstant(ZONE).toLocalDate()).param("at",occurredAt).param("type","BONUS".equals(caseType)?"BONUS":"PENALTY").query(Long.class).single();
        if(count==0)throw new ApiException("INVALID_RULE","积分规则不存在、类型不符或在发生日期无效，请重新选择",HttpStatus.BAD_REQUEST);
    }
    private Target target(UUID id){List<String> roles=jdbc.sql("SELECT role_code FROM role_binding WHERE user_id=:id").param("id",id).query(String.class).list();UUID team=jdbc.sql("SELECT org_unit_id FROM app_user WHERE id=:id AND status='ACTIVE'").param("id",id).query(UUID.class).optional().orElseThrow(()->new ApiException("USER_NOT_FOUND","有效人员不存在",HttpStatus.NOT_FOUND));return new Target(team,new HashSet<>(roles));}
    private void requireScored(CurrentUser actor){if(actor.roles().stream().noneMatch(BusinessRoles.SCORED::contains))forbidden();}
    private void requireSupervisor(CurrentUser actor){if(!actor.roles().contains(BusinessRoles.SUPERVISOR))forbidden();}
    private void validateOccurredThisMonth(OffsetDateTime occurredAt){if(occurredAt==null||!YearMonth.from(occurredAt.atZoneSameInstant(ZONE)).equals(YearMonth.now(ZONE)))throw new ApiException("BONUS_MONTH_CLOSED","加分事项只能在发生当月申请",HttpStatus.BAD_REQUEST);}
    private void requireDescription(String value){if(value==null||value.isBlank()||value.length()>2000)throw new ApiException("INVALID_DESCRIPTION","说明为必填且不能超过2000字",HttpStatus.BAD_REQUEST);}
    private void validateAttachments(CurrentUser actor,List<UUID> ids){if(ids==null||ids.isEmpty())return;if(ids.stream().distinct().count()>9)throw new ApiException("TOO_MANY_ATTACHMENTS","每次最多上传9张图片",HttpStatus.BAD_REQUEST);Long count=jdbc.sql("SELECT COUNT(*) FROM attachment WHERE owner_id=:owner AND id IN (:ids) AND mime_type LIKE 'image/%'").param("owner",actor.userId()).param("ids",ids).query(Long.class).single();if(count!=ids.stream().distinct().count())throw new ApiException("INVALID_ATTACHMENTS","附件不存在、不是图片或不属于当前用户",HttpStatus.BAD_REQUEST);}
    private void linkAttachments(UUID caseId,List<UUID> ids){if(ids==null)return;ids.stream().distinct().forEach(id->jdbc.sql("INSERT INTO performance_case_attachment(case_id,attachment_id) VALUES (:caseId,:attachmentId)").param("caseId",caseId).param("attachmentId",id).update());}

    private void notifyNextReviewer(UUID id,CurrentUser actor,String stage,String title){if("ASSISTANT".equals(stage)){List<UUID> users=jdbc.sql("SELECT DISTINCT rb.user_id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id WHERE rb.role_code='ASSISTANT_ENGINEER' AND u.org_unit_id=:teamId AND u.status='ACTIVE'").param("teamId",actor.orgUnitId()).query(UUID.class).list();users.forEach(uid->notifications.create(uid,"PERFORMANCE_PENDING",title,"请在24小时内处理","PERFORMANCE_CASE",id,false));}else notifySupervisors(id,title);}
    private void notifySupervisors(UUID id,String title){jdbc.sql("SELECT DISTINCT rb.user_id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE'").query(UUID.class).list().forEach(uid->notifications.create(uid,"PERFORMANCE_PENDING",title,"请在24小时内处理","PERFORMANCE_CASE",id,false));}
    private void notifyEligibleSupervisors(CaseView item,String title){jdbc.sql("SELECT DISTINCT rb.user_id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE' AND rb.user_id<>:excluded").param("excluded",item.originalSupervisorId()).query(UUID.class).list().forEach(uid->notifications.create(uid,"APPEAL_PENDING",title,"请在24小时内完成表决","PERFORMANCE_CASE",item.id(),false));}

    private void action(UUID caseId,UUID actorId,String action,String stage,String comment,Integer score,String from,String to){jdbc.sql("INSERT INTO performance_case_action(id,case_id,actor_id,action,stage,comment,score,from_status,to_status) VALUES (:id,:caseId,:actorId,:action,:stage,:comment,:score,:from,:to)").param("id",UUID.randomUUID()).param("caseId",caseId).param("actorId",actorId).param("action",action).param("stage",stage).param("comment",clean(comment)).param("score",score).param("from",from).param("to",to).update();}
    private void updateState(UUID id,long version,String status,String stage,OffsetDateTime due){int n=jdbc.sql("UPDATE performance_case SET status=:status,current_stage=:stage,due_at=:due,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").param("status",status).param("stage",stage).param("due",due).param("id",id).param("version",version).update();if(n!=1)conflict();}

    private List<CaseView> query(String where,Map<String,Object> params){var spec=jdbc.sql(caseSelect()+where+" ORDER BY p.created_at DESC LIMIT 500");for(var entry:params.entrySet())spec=spec.param(entry.getKey(),entry.getValue());return spec.query(this::mapCase).list();}
    private CaseView get(UUID id){return jdbc.sql(caseSelect()+" WHERE p.id=:id").param("id",id).query(this::mapCase).optional().orElseThrow(()->new ApiException("CASE_NOT_FOUND","绩效事项不存在",HttpStatus.NOT_FOUND));}
    private CaseView getForUpdate(UUID id){jdbc.sql("SELECT id FROM performance_case WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().orElseThrow(()->new ApiException("CASE_NOT_FOUND","绩效事项不存在",HttpStatus.NOT_FOUND));return get(id);}
    private String caseSelect(){return "SELECT p.id,p.case_type,p.target_user_id,target.employee_no,target.display_name,p.initiated_by,initiator.display_name initiator_name,target.org_unit_id target_team_id,team.name target_team_name,p.occurred_at,p.description,p.rule_code,p.suggested_score,p.approved_score,p.effective_score,p.status,p.current_stage,p.direct_by_supervisor,p.original_supervisor_id,p.due_at,p.appeal_deadline_at,ARRAY(SELECT pca.attachment_id FROM performance_case_attachment pca WHERE pca.case_id=p.id ORDER BY pca.attachment_id) attachment_ids,p.version,p.created_at,p.updated_at FROM performance_case p JOIN app_user target ON target.id=p.target_user_id JOIN app_user initiator ON initiator.id=p.initiated_by JOIN org_unit team ON team.id=target.org_unit_id";}
    private CaseView mapCase(java.sql.ResultSet rs,int n)throws java.sql.SQLException{UUID id=rs.getObject("id",UUID.class);java.sql.Array attachmentArray=rs.getArray("attachment_ids");List<UUID> attachments=attachmentArray==null?List.of():Arrays.stream((Object[])attachmentArray.getArray()).map(value->value instanceof UUID uuid?uuid:UUID.fromString(String.valueOf(value))).toList();return new CaseView(id,rs.getString("case_type"),rs.getObject("target_user_id",UUID.class),rs.getString("employee_no"),rs.getString("display_name"),rs.getObject("initiated_by",UUID.class),rs.getString("initiator_name"),rs.getObject("target_team_id",UUID.class),rs.getString("target_team_name"),rs.getObject("occurred_at",OffsetDateTime.class),rs.getString("description"),rs.getString("rule_code"),(Integer)rs.getObject("suggested_score"),(Integer)rs.getObject("approved_score"),(Integer)rs.getObject("effective_score"),rs.getString("status"),rs.getString("current_stage"),rs.getBoolean("direct_by_supervisor"),rs.getObject("original_supervisor_id",UUID.class),rs.getObject("due_at",OffsetDateTime.class),rs.getObject("appeal_deadline_at",OffsetDateTime.class),attachments,rs.getLong("version"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("updated_at",OffsetDateTime.class));}

    private AppealView appealView(UUID id){return jdbc.sql(appealSelect()+" WHERE a.id=:id").param("id",id).query(this::mapAppeal).optional().orElseThrow(()->new ApiException("APPEAL_NOT_FOUND","申诉不存在",HttpStatus.NOT_FOUND));}
    private AppealView appealViewForUpdate(UUID id){jdbc.sql("SELECT id FROM performance_appeal WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().orElseThrow(()->new ApiException("APPEAL_NOT_FOUND","申诉不存在",HttpStatus.NOT_FOUND));return appealView(id);}
    private String appealSelect(){return "SELECT a.id,a.case_id,a.applicant_id,u.display_name applicant_name,a.description,a.status,a.due_at,a.version,a.created_at,a.decided_at,(SELECT COUNT(*) FROM performance_appeal_vote v WHERE v.appeal_id=a.id AND v.decision='APPROVE') approvals,(SELECT COUNT(*) FROM performance_appeal_vote v WHERE v.appeal_id=a.id AND v.decision='REJECT') rejections FROM performance_appeal a JOIN app_user u ON u.id=a.applicant_id";}
    private AppealView mapAppeal(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new AppealView(rs.getObject("id",UUID.class),rs.getObject("case_id",UUID.class),rs.getObject("applicant_id",UUID.class),rs.getString("applicant_name"),rs.getString("description"),rs.getString("status"),rs.getObject("due_at",OffsetDateTime.class),rs.getInt("approvals"),rs.getInt("rejections"),rs.getLong("version"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("decided_at",OffsetDateTime.class));}
    private ApiException invalidDecision(){return new ApiException("INVALID_DECISION","审核操作不合法",HttpStatus.BAD_REQUEST);}
    private void invalid(){throw new ApiException("INVALID_CASE","绩效事项内容不合法",HttpStatus.BAD_REQUEST);}
    private void forbidden(){throw new ApiException("FORBIDDEN","当前角色或数据范围无权执行此操作",HttpStatus.FORBIDDEN);}
    private void conflict(){throw new ApiException("VERSION_CONFLICT","事项已被其他人处理，请刷新后重试",HttpStatus.CONFLICT);}
    private String clean(String value){return value==null||value.isBlank()?null:value.trim();}

    private record Target(UUID teamId,Set<String> roles){}
    public record DeductionInput(String caseType,UUID targetUserId,OffsetDateTime occurredAt,String description,String ruleCode,Integer score,List<UUID> attachmentIds,UUID incidentId){}
    public record CaseView(UUID id,String caseType,UUID targetUserId,String employeeNo,String targetName,UUID initiatedBy,String initiatorName,UUID targetTeamId,String targetTeamName,OffsetDateTime occurredAt,String description,String ruleCode,Integer suggestedScore,Integer approvedScore,Integer effectiveScore,String status,String currentStage,boolean directBySupervisor,UUID originalSupervisorId,OffsetDateTime dueAt,OffsetDateTime appealDeadlineAt,List<UUID> attachmentIds,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
    public record AppealView(UUID id,UUID caseId,UUID applicantId,String applicantName,String description,String status,OffsetDateTime dueAt,int approvals,int rejections,long version,OffsetDateTime createdAt,OffsetDateTime decidedAt){}
}
