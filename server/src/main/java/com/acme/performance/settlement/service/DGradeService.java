package com.acme.performance.settlement.service;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.organization.service.DataScopeService;
import com.acme.performance.organization.model.BusinessRoles;
import com.acme.performance.common.service.BusinessDeadlineService;
import com.acme.performance.notification.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneId;

@Service
public class DGradeService {
    private static final Set<String> REVIEW_ROLES = Set.of(BusinessRoles.SUPERVISOR);
    private final JdbcClient jdbc;
    private final DataScopeService dataScope;
    private final AuditLogService audit;
    private final BusinessDeadlineService deadlines;
    private final NotificationService notifications;

    public DGradeService(JdbcClient jdbc, DataScopeService dataScope, AuditLogService audit,
                         BusinessDeadlineService deadlines, NotificationService notifications) {
        this.jdbc = jdbc; this.dataScope = dataScope; this.audit = audit;
        this.deadlines = deadlines; this.notifications = notifications;
    }

    @Transactional
    public UUID nominate(CurrentUser actor, String period, UUID userId, String reasonCode, String description,
                         UUID evidenceAttachmentId, String requestId) {
        requireReviewer(actor); validatePeriod(period);
        if (!dataScope.canAccessUser(actor, userId)) throw new ApiException("FORBIDDEN", "无权提名该员工", HttpStatus.FORBIDDEN);
        UUID orgId = jdbc.sql("""
                SELECT u.org_unit_id FROM app_user u WHERE u.id=:id AND u.status='ACTIVE'
                  AND EXISTS (SELECT 1 FROM role_binding r WHERE r.user_id=u.id AND r.role_code IN ('TECHNICIAN','ASSISTANT_ENGINEER'))
                """).param("id", userId)
                .query(UUID.class).optional().orElseThrow(() -> new ApiException("USER_NOT_FOUND", "员工不存在或已停用", HttpStatus.NOT_FOUND));
        if (evidenceAttachmentId != null) {
            Long evidence = jdbc.sql("SELECT COUNT(*) FROM attachment WHERE id=:id AND owner_id=:ownerId")
                    .param("id", evidenceAttachmentId).param("ownerId", actor.userId()).query(Long.class).single();
            if (evidence != 1) throw new ApiException("INVALID_D_EVIDENCE", "D级证据不存在或不属于提名人", HttpStatus.BAD_REQUEST);
        }
        if (reasonCode == null || reasonCode.isBlank() || description == null || description.isBlank()) {
            throw new ApiException("D_REASON_REQUIRED", "D级提名必须填写原因和说明", HttpStatus.BAD_REQUEST);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO d_grade_nomination(id,period,user_id,org_unit_id,reason_code,description,evidence_attachment_id,nominated_by)
                    VALUES (:id,:period,:userId,:orgId,:reason,:description,:evidence,:actorId)
                    """).param("id", id).param("period", period).param("userId", userId).param("orgId", orgId)
                    .param("reason", reasonCode).param("description", description).param("evidence", evidenceAttachmentId)
                    .param("actorId", actor.userId()).update();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ApiException("D_NOMINATION_EXISTS", "该员工本周期已有D级提名", HttpStatus.CONFLICT);
        }
        jdbc.sql("""
                INSERT INTO d_grade_confirmation(id,nomination_id,reviewer_id,decision,comment)
                VALUES (:id,:nominationId,:reviewerId,'APPROVE','发起提名')
                """).param("id", UUID.randomUUID()).param("nominationId", id).param("reviewerId", actor.userId()).update();
        audit.record(actor.userId(), "D_GRADE_NOMINATE", "D_GRADE_NOMINATION", id, "{\"status\":\"PENDING\"}", requestId);
        return id;
    }

    @Transactional
    public ConfirmationResult confirm(CurrentUser actor, UUID nominationId, String decision, String comment, String requestId) {
        requireReviewer(actor);
        Nomination row = jdbc.sql("SELECT user_id,status FROM d_grade_nomination WHERE id=:id").param("id", nominationId)
                .query((rs, n) -> new Nomination(rs.getObject("user_id", UUID.class), rs.getString("status"))).optional()
                .orElseThrow(() -> new ApiException("D_NOMINATION_NOT_FOUND", "D级提名不存在", HttpStatus.NOT_FOUND));
        if (!dataScope.canAccessUser(actor, row.userId())) throw new ApiException("FORBIDDEN", "无权复核该提名", HttpStatus.FORBIDDEN);
        if (!Set.of("PENDING", "CONFIRMED").contains(row.status())) throw new ApiException("INVALID_D_STATUS", "当前状态不能复核", HttpStatus.CONFLICT);
        String normalized = decision == null ? "" : decision.toUpperCase();
        if (!Set.of("APPROVE", "REJECT").contains(normalized)) throw new ApiException("INVALID_DECISION", "确认结果不合法", HttpStatus.BAD_REQUEST);
        jdbc.sql("""
                INSERT INTO d_grade_confirmation(id,nomination_id,reviewer_id,decision,comment)
                VALUES (:id,:nominationId,:reviewerId,:decision,:comment) ON CONFLICT (nomination_id,reviewer_id) DO NOTHING
                """).param("id", UUID.randomUUID()).param("nominationId", nominationId).param("reviewerId", actor.userId())
                .param("decision", normalized).param("comment", comment).update();
        Long confirmations = jdbc.sql("SELECT COUNT(*) FROM d_grade_confirmation WHERE nomination_id=:id AND decision='APPROVE'")
                .param("id", nominationId).query(Long.class).single();
        Long rejections = jdbc.sql("SELECT COUNT(*) FROM d_grade_confirmation WHERE nomination_id=:id AND decision='REJECT'")
                .param("id", nominationId).query(Long.class).single();
        String status = rejections > 0 ? "REJECTED" : confirmations >= 3 ? "CONFIRMED" : "PENDING";
        if ("CONFIRMED".equals(status)) {
            OffsetDateTime appealDeadline = deadlines.addHours(OffsetDateTime.now(ZoneId.of("Asia/Shanghai")), 24, row.userId());
            jdbc.sql("UPDATE d_grade_nomination SET status='CONFIRMED',confirmed_at=CURRENT_TIMESTAMP,appeal_deadline_at=:deadline WHERE id=:id")
                    .param("deadline", appealDeadline).param("id", nominationId).update();
            notifications.create(row.userId(), "D_GRADE_CONFIRMED", "D级认定已生效", "您可以在24小时内提交一次申诉。", "D_GRADE_NOMINATION", nominationId, false);
        } else if ("REJECTED".equals(status)) {
            jdbc.sql("UPDATE d_grade_nomination SET status='REJECTED' WHERE id=:id").param("id", nominationId).update();
        }
        audit.record(actor.userId(), "D_GRADE_CONFIRM", "D_GRADE_NOMINATION", nominationId,
                "{\"confirmations\":" + confirmations + ",\"status\":\"" + status + "\"}", requestId);
        return new ConfirmationResult(nominationId, confirmations.intValue(), status);
    }

    @Transactional
    public AppealView appeal(CurrentUser actor, UUID nominationId, String description, String requestId) {
        NominationDetail row=jdbc.sql("SELECT user_id,status,appeal_deadline_at FROM d_grade_nomination WHERE id=:id FOR UPDATE")
                .param("id",nominationId).query((rs,n)->new NominationDetail(rs.getObject("user_id",UUID.class),rs.getString("status"),rs.getObject("appeal_deadline_at",OffsetDateTime.class)))
                .optional().orElseThrow(()->new ApiException("D_NOMINATION_NOT_FOUND","D级认定不存在",HttpStatus.NOT_FOUND));
        if(!row.userId().equals(actor.userId())||!"CONFIRMED".equals(row.status()))throw new ApiException("FORBIDDEN","只能对本人已生效的D级认定申诉",HttpStatus.FORBIDDEN);
        if(row.appealDeadline()==null||deadlines.isPast(row.appealDeadline()))throw new ApiException("D_APPEAL_EXPIRED","D级认定生效后24小时内才可申诉",HttpStatus.CONFLICT);
        if(description==null||description.isBlank())throw new ApiException("D_APPEAL_REASON_REQUIRED","请填写D级申诉理由",HttpStatus.BAD_REQUEST);
        UUID id=UUID.randomUUID();OffsetDateTime due=deadlines.addHours(OffsetDateTime.now(ZoneId.of("Asia/Shanghai")),24,actor.userId());
        try{jdbc.sql("INSERT INTO d_grade_appeal(id,nomination_id,applicant_id,description,status,due_at) VALUES (:id,:nominationId,:applicantId,:description,'PENDING',:dueAt)").param("id",id).param("nominationId",nominationId).param("applicantId",actor.userId()).param("description",description.trim()).param("dueAt",due).update();}
        catch(org.springframework.dao.DataIntegrityViolationException ex){throw new ApiException("D_APPEAL_EXISTS","每条D级认定只能申诉一次",HttpStatus.CONFLICT);}
        notifySupervisors(id,"新的D级申诉待表决","达到当前表决通过条件后可撤销D级认定。");audit.record(actor.userId(),"D_GRADE_APPEAL","D_GRADE_APPEAL",id,"{}",requestId);return appealView(id);
    }

    @Transactional
    public AppealView voteAppeal(CurrentUser actor,UUID appealId,String decision,String comment,String requestId){
        requireReviewer(actor);AppealView item=appealViewForUpdate(appealId);if(!"PENDING".equals(item.status()))throw new ApiException("D_APPEAL_HANDLED","D级申诉已经结束",HttpStatus.CONFLICT);
        String normalized=decision==null?"":decision.toUpperCase();if(!Set.of("APPROVE","REJECT").contains(normalized))throw new ApiException("INVALID_DECISION","表决结果不合法",HttpStatus.BAD_REQUEST);
        try{jdbc.sql("INSERT INTO d_grade_appeal_vote(id,appeal_id,supervisor_id,decision,comment) VALUES (:id,:appealId,:actorId,:decision,:comment)").param("id",UUID.randomUUID()).param("appealId",appealId).param("actorId",actor.userId()).param("decision",normalized).param("comment",comment==null||comment.isBlank()?null:comment.trim()).update();}
        catch(org.springframework.dao.DataIntegrityViolationException ex){throw new ApiException("D_APPEAL_ALREADY_VOTED","每位主管只能表决一次",HttpStatus.CONFLICT);}
        if("REJECT".equals(normalized))finishAppeal(item,"REJECTED");else{long supervisors=activeSupervisorCount(),approvals=jdbc.sql("SELECT COUNT(*) FROM d_grade_appeal_vote WHERE appeal_id=:id AND decision='APPROVE'").param("id",appealId).query(Long.class).single();if(supervisors>0&&approvals>=supervisors)finishAppeal(item,"ACCEPTED");}
        audit.record(actor.userId(),"D_GRADE_APPEAL_VOTE_"+normalized,"D_GRADE_APPEAL",appealId,"{}",requestId);return appealView(appealId);
    }

    @Transactional(readOnly=true)
    public List<AppealView> pendingAppeals(CurrentUser actor){if(!actor.roles().contains(BusinessRoles.SUPERVISOR)&&!actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER))throw new ApiException("FORBIDDEN","无权查看D级申诉",HttpStatus.FORBIDDEN);return jdbc.sql(appealSelect()+" WHERE a.status='PENDING' ORDER BY a.due_at").query(this::mapAppeal).list();}

    @Transactional
    public int expireAppeals(){List<AppealView> expired=jdbc.sql(appealSelect()+" WHERE a.status='PENDING' AND a.due_at<CURRENT_TIMESTAMP FOR UPDATE OF a SKIP LOCKED").query(this::mapAppeal).list();expired.forEach(item->finishAppeal(item,"REJECTED_TIMEOUT"));return expired.size();}

    private void finishAppeal(AppealView item,String status){jdbc.sql("UPDATE d_grade_appeal SET status=:status,decided_at=CURRENT_TIMESTAMP WHERE id=:id AND status='PENDING'").param("status",status).param("id",item.id()).update();if("ACCEPTED".equals(status))jdbc.sql("UPDATE d_grade_nomination SET status='REVERSED' WHERE id=:id AND status='CONFIRMED'").param("id",item.nominationId()).update();notifications.create(item.applicantId(),"D_GRADE_APPEAL_RESULT","D级申诉结果",switch(status){case "ACCEPTED"->"申诉通过，D级认定已撤销。";case "REJECTED_TIMEOUT"->"申诉逾期未达到当前表决条件，已自动驳回。";default->"申诉未通过。";},"D_GRADE_NOMINATION",item.nominationId(),false);}
    private void notifySupervisors(UUID sourceId,String title,String content){jdbc.sql("SELECT DISTINCT rb.user_id FROM role_binding rb JOIN app_user u ON u.id=rb.user_id WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE'").query(UUID.class).list().forEach(id->notifications.create(id,"D_GRADE_APPEAL_PENDING",title,content,"D_GRADE_APPEAL",sourceId,false));}
    private long activeSupervisorCount(){return jdbc.sql("SELECT COUNT(DISTINCT rb.user_id) FROM role_binding rb JOIN app_user u ON u.id=rb.user_id WHERE rb.role_code='SUPERVISOR' AND u.status='ACTIVE'").query(Long.class).single();}
    private String appealSelect(){return "SELECT a.id,a.nomination_id,a.applicant_id,u.display_name applicant_name,a.description,a.status,a.due_at,a.created_at,a.decided_at,(SELECT COUNT(*) FROM d_grade_appeal_vote v WHERE v.appeal_id=a.id AND v.decision='APPROVE') approvals,(SELECT COUNT(*) FROM d_grade_appeal_vote v WHERE v.appeal_id=a.id AND v.decision='REJECT') rejections FROM d_grade_appeal a JOIN app_user u ON u.id=a.applicant_id";}
    private AppealView appealView(UUID id){return jdbc.sql(appealSelect()+" WHERE a.id=:id").param("id",id).query(this::mapAppeal).optional().orElseThrow(()->new ApiException("D_APPEAL_NOT_FOUND","D级申诉不存在",HttpStatus.NOT_FOUND));}
    private AppealView appealViewForUpdate(UUID id){jdbc.sql("SELECT id FROM d_grade_appeal WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().orElseThrow(()->new ApiException("D_APPEAL_NOT_FOUND","D级申诉不存在",HttpStatus.NOT_FOUND));return appealView(id);}
    private AppealView mapAppeal(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new AppealView(rs.getObject("id",UUID.class),rs.getObject("nomination_id",UUID.class),rs.getObject("applicant_id",UUID.class),rs.getString("applicant_name"),rs.getString("description"),rs.getString("status"),rs.getObject("due_at",OffsetDateTime.class),rs.getInt("approvals"),rs.getInt("rejections"),rs.getObject("created_at",OffsetDateTime.class),rs.getObject("decided_at",OffsetDateTime.class));}

    @Transactional(readOnly = true)
    public List<NominationView> nominations(CurrentUser actor, String period) {
        if (!actor.roles().contains(BusinessRoles.SUPERVISOR) && !actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER))
            throw new ApiException("FORBIDDEN", "当前角色不能查看D级认定", HttpStatus.FORBIDDEN);
        validatePeriod(period);
        return jdbc.sql("""
                SELECT n.id,n.user_id,u.display_name,u.employee_no,n.reason_code,n.description,n.evidence_attachment_id,n.status,n.appeal_deadline_at,
                       (SELECT COUNT(*) FROM d_grade_confirmation c WHERE c.nomination_id=n.id) confirmations
                FROM d_grade_nomination n JOIN app_user u ON u.id=n.user_id WHERE n.period=:period ORDER BY n.created_at
                """).param("period", period).query((rs, n) -> new NominationView(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("display_name"), rs.getString("employee_no"),
                        rs.getString("reason_code"), rs.getString("description"), rs.getObject("evidence_attachment_id", UUID.class),
                        rs.getString("status"), rs.getInt("confirmations"),rs.getObject("appeal_deadline_at",OffsetDateTime.class))).list().stream()
                .filter(item -> dataScope.canAccessUser(actor, item.userId())).toList();
    }

    @Transactional(readOnly=true)
    public List<NominationView> mine(CurrentUser actor){return jdbc.sql("""
            SELECT n.id,n.user_id,u.display_name,u.employee_no,n.reason_code,n.description,n.evidence_attachment_id,n.status,n.appeal_deadline_at,
              (SELECT COUNT(*) FROM d_grade_confirmation c WHERE c.nomination_id=n.id) confirmations
            FROM d_grade_nomination n JOIN app_user u ON u.id=n.user_id WHERE n.user_id=:userId ORDER BY n.created_at DESC
            """).param("userId",actor.userId()).query((rs,n)->new NominationView(rs.getObject("id",UUID.class),rs.getObject("user_id",UUID.class),rs.getString("display_name"),rs.getString("employee_no"),rs.getString("reason_code"),rs.getString("description"),rs.getObject("evidence_attachment_id",UUID.class),rs.getString("status"),rs.getInt("confirmations"),rs.getObject("appeal_deadline_at",OffsetDateTime.class))).list();}

    private void requireReviewer(CurrentUser actor) {
        if (actor.roles().stream().noneMatch(REVIEW_ROLES::contains)) throw new ApiException("FORBIDDEN", "仅主管可以处理D级认定", HttpStatus.FORBIDDEN);
    }
    public static void validatePeriod(String period) {
        if (period == null || !period.matches("\\d{4}-(0[1-9]|1[0-2])")) throw new ApiException("INVALID_PERIOD", "周期格式必须为YYYY-MM", HttpStatus.BAD_REQUEST);
    }
    public record ConfirmationResult(UUID nominationId, int confirmations, String status) {}
    public record NominationView(UUID id, UUID userId, String displayName, String employeeNo, String reasonCode,
                                 String description, UUID evidenceAttachmentId, String status, int confirmations,OffsetDateTime appealDeadlineAt) {}
    private record Nomination(UUID userId, String status) {}
    private record NominationDetail(UUID userId,String status,OffsetDateTime appealDeadline) {}
    public record AppealView(UUID id,UUID nominationId,UUID applicantId,String applicantName,String description,String status,OffsetDateTime dueAt,int approvals,int rejections,OffsetDateTime createdAt,OffsetDateTime decidedAt) {}
}
