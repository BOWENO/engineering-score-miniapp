package com.acme.performance.schedule.service;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.notification.service.NotificationService;
import com.acme.performance.organization.model.BusinessRoles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ScheduleService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public ScheduleService(JdbcClient jdbc, NotificationService notifications,
                           AuditLogService audit, ObjectMapper mapper) {
        this.jdbc = jdbc; this.notifications = notifications; this.audit = audit; this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<AssignmentView> list(CurrentUser actor, LocalDate from, LocalDate to, UUID userId, String status) {
        LocalDate safeFrom = from == null ? LocalDate.now(ZONE).minusMonths(1) : from;
        LocalDate safeTo = to == null ? LocalDate.now(ZONE).plusMonths(3) : to;
        if (safeFrom.isAfter(safeTo) || ChronoUnit.DAYS.between(safeFrom, safeTo) > 730) {
            throw new ApiException("INVALID_DATE_RANGE", "排班查询日期范围不合法", HttpStatus.BAD_REQUEST);
        }
        Scope scope = scope(actor);
        UUID target = userId;
        if (scope == Scope.SELF) target = actor.userId();
        StringBuilder where = new StringBuilder(" WHERE s.business_date BETWEEN :from AND :to AND u.is_review_account=:reviewAccount ");
        if (target != null) where.append(" AND s.user_id=:target ");
        if (scope == Scope.TEAM) where.append(" AND s.team_id=:teamId ");
        if (scope == Scope.SELF) where.append(" AND s.status='PUBLISHED' ");
        if (status != null && !status.isBlank()) where.append(" AND s.status=:status ");
        var query = jdbc.sql(baseSelect() + where + " ORDER BY s.business_date,s.shift_starts_at,l.name,st.name,u.employee_no")
                .param("from", safeFrom).param("to", safeTo).param("reviewAccount", "wxreview".equalsIgnoreCase(actor.employeeNo()));
        if (target != null) query = query.param("target", target);
        if (scope == Scope.TEAM) query = query.param("teamId", actor.orgUnitId());
        if (status != null && !status.isBlank()) query = query.param("status", status.toUpperCase());
        return query.query(this::map).list();
    }

    @Transactional
    public BatchResult createBatch(CurrentUser actor, BatchRequest request, String requestId) {
        requireScheduler(actor);
        validateBatch(request);
        ShiftRow shift = shift(request.shiftCode(), request.from());
        List<UUID> users = request.userIds().stream().distinct().toList();
        users.forEach(userId -> requireAssignable(actor, userId));
        requireLineStation(request.lineId(), request.stationIds());
        int created = 0;
        int existing = 0;
        List<UUID> ids = new ArrayList<>();
        for (LocalDate date = request.from(); !date.isAfter(request.to()); date = date.plusDays(1)) {
            ShiftTimes times = times(date, shift);
            for (UUID stationId : request.stationIds()) {
                ensureStationCapacity(date, shift.code(), stationId, users);
                for (UUID userId : users) {
                    UUID existingId = jdbc.sql("""
                            SELECT id FROM schedule_assignment WHERE user_id=:userId AND business_date=:date
                              AND shift_code=:shiftCode AND line_id=:lineId AND station_id=:stationId
                            """).param("userId", userId).param("date", date).param("shiftCode", shift.code())
                            .param("lineId", request.lineId()).param("stationId", stationId).query(UUID.class).optional().orElse(null);
                    if (existingId != null) { existing++; ids.add(existingId); continue; }
                    UUID id = UUID.randomUUID();
                    UUID teamId = userTeam(userId);
                    String state = request.publish() ? "PUBLISHED" : "DRAFT";
                    jdbc.sql("""
                            INSERT INTO schedule_assignment(id,user_id,team_id,business_date,shift_id,shift_code,
                              shift_starts_at,shift_ends_at,line_id,station_id,status,created_by,published_by,published_at,acknowledged_at)
                            VALUES (:id,:userId,:teamId,:date,:shiftId,:shiftCode,:startsAt,:endsAt,:lineId,:stationId,
                              :status,:actorId,:publishedBy,:publishedAt,:publishedAt)
                            """).param("id", id).param("userId", userId).param("teamId", teamId).param("date", date)
                            .param("shiftId", shift.id()).param("shiftCode", shift.code()).param("startsAt", times.startsAt())
                            .param("endsAt", times.endsAt()).param("lineId", request.lineId()).param("stationId", stationId)
                            .param("status", state).param("actorId", actor.userId())
                            .param("publishedBy", request.publish() ? actor.userId() : null)
                            .param("publishedAt", request.publish() ? OffsetDateTime.now(ZONE) : null).update();
                    recordRevision(id, actor.userId(), "CREATE", request.reason(), null, get(id));
                    if (request.publish()) onPublished(id, userId, date, shift.code(), times.endsAt());
                    created++; ids.add(id);
                }
            }
        }
        BatchResult result = new BatchResult(created, existing, ids);
        audit.record(actor.userId(), "SCHEDULE_BATCH_CREATE", "SCHEDULE_ASSIGNMENT", null, json(result), requestId);
        return result;
    }

    @Transactional
    public AssignmentView publish(CurrentUser actor, UUID id, long version, String requestId) {
        requireScheduler(actor);
        AssignmentView before = get(id);
        requireAssignable(actor, before.userId());
        if (!"DRAFT".equals(before.status())) return before;
        ensureStationCapacity(before.businessDate(), before.shiftCode(), before.stationId(), List.of(before.userId()));
        int updated = jdbc.sql("""
                UPDATE schedule_assignment SET status='PUBLISHED',published_by=:actorId,published_at=CURRENT_TIMESTAMP,
                  acknowledged_at=CURRENT_TIMESTAMP,version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND version=:version AND status='DRAFT'
                """).param("actorId", actor.userId()).param("id", id).param("version", version).update();
        if (updated != 1) conflict();
        AssignmentView result = get(id);
        recordRevision(id, actor.userId(), "PUBLISH", null, before, result);
        onPublished(id, result.userId(), result.businessDate(), result.shiftCode(), result.shiftEndsAt());
        audit.record(actor.userId(), "SCHEDULE_PUBLISH", "SCHEDULE_ASSIGNMENT", id, json(result), requestId);
        return result;
    }

    @Transactional
    public AssignmentView cancel(CurrentUser actor, UUID id, long version, String reason, String requestId) {
        requireScheduler(actor);
        AssignmentView before = get(id);
        requireAssignable(actor, before.userId());
        boolean started = before.shiftStartsAt().isBefore(OffsetDateTime.now(ZONE));
        if (started && !actor.roles().contains(BusinessRoles.SUPERVISOR)) {
            throw new ApiException("SCHEDULE_ALREADY_STARTED", "班次开始后只能由主管纠正排班", HttpStatus.CONFLICT);
        }
        if (started && (reason == null || reason.isBlank())) {
            throw new ApiException("CHANGE_REASON_REQUIRED", "班次开始后的排班纠正必须填写原因", HttpStatus.BAD_REQUEST);
        }
        int updated = jdbc.sql("""
                UPDATE schedule_assignment SET status='CANCELLED',version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND version=:version AND status<>'CANCELLED'
                """).param("id", id).param("version", version).update();
        if (updated != 1) conflict();
        AssignmentView result = get(id);
        recordRevision(id, actor.userId(), "CANCEL", reason, before, result);
        refreshShiftScore(result.userId(), result.businessDate(), result.shiftCode(), result.shiftEndsAt());
        notifications.create(result.userId(), "SCHEDULE_CHANGED", "排班已变更",
                result.businessDate() + " " + shiftName(result.shiftCode()) + "的责任排班已变更，请查看，无需确认。",
                "SCHEDULE_ASSIGNMENT", id, false);
        audit.record(actor.userId(), "SCHEDULE_CANCEL", "SCHEDULE_ASSIGNMENT", id, json(result), requestId);
        return result;
    }

    @Transactional
    public AssignmentView acknowledge(CurrentUser actor, UUID id) {
        int updated = jdbc.sql("""
                UPDATE schedule_assignment SET acknowledged_at=COALESCE(acknowledged_at,CURRENT_TIMESTAMP)
                WHERE id=:id AND user_id=:userId AND status='PUBLISHED'
                """).param("id", id).param("userId", actor.userId()).update();
        if (updated != 1) throw new ApiException("SCHEDULE_NOT_FOUND", "排班不存在", HttpStatus.NOT_FOUND);
        jdbc.sql("""
                UPDATE notification SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP),
                  acknowledged_at=COALESCE(acknowledged_at,CURRENT_TIMESTAMP)
                WHERE user_id=:userId AND source_type='SCHEDULE_ASSIGNMENT' AND source_id=:id
                """).param("userId", actor.userId()).param("id", id).update();
        return get(id);
    }

    private void onPublished(UUID assignmentId, UUID userId, LocalDate date, String shiftCode, OffsetDateTime endsAt) {
        refreshShiftScore(userId, date, shiftCode, endsAt);
        AssignmentView item = get(assignmentId);
        notifications.create(userId, "SCHEDULE_PUBLISHED", "新排班已发布",
                date + " " + shiftName(shiftCode) + " · " + item.lineName() + " / " + item.stationName(),
                "SCHEDULE_ASSIGNMENT", assignmentId, false);
    }

    private void refreshShiftScore(UUID userId, LocalDate date, String shiftCode, OffsetDateTime endsAt) {
        Long active = jdbc.sql("""
                SELECT COUNT(*) FROM schedule_assignment WHERE user_id=:userId AND business_date=:date
                  AND shift_code=:shiftCode AND status='PUBLISHED'
                """).param("userId", userId).param("date", date).param("shiftCode", shiftCode).query(Long.class).single();
        if (active > 0) {
            jdbc.sql("""
                    INSERT INTO shift_score(id,user_id,business_date,shift_code,shift_ends_at,status)
                    VALUES (:id,:userId,:date,:shiftCode,:endsAt,'PENDING')
                    ON CONFLICT (user_id,business_date,shift_code) DO UPDATE
                      SET shift_ends_at=EXCLUDED.shift_ends_at,status=CASE WHEN shift_score.status='POSTED' THEN 'POSTED' ELSE 'PENDING' END
                    """).param("id", UUID.randomUUID()).param("userId", userId).param("date", date)
                    .param("shiftCode", shiftCode).param("endsAt", endsAt).update();
        } else {
            jdbc.sql("""
                    UPDATE shift_score SET status='CANCELLED' WHERE user_id=:userId AND business_date=:date
                      AND shift_code=:shiftCode AND status='PENDING'
                    """).param("userId", userId).param("date", date).param("shiftCode", shiftCode).update();
        }
    }

    private void validateBatch(BatchRequest request) {
        if (request == null || request.from() == null || request.to() == null || request.from().isAfter(request.to())
                || ChronoUnit.DAYS.between(request.from(), request.to()) > 92 || request.userIds() == null
                || request.userIds().isEmpty() || request.userIds().size() > 100 || request.stationIds() == null
                || request.stationIds().isEmpty() || request.stationIds().size() > 100 || request.lineId() == null) {
            throw new ApiException("INVALID_SCHEDULE_BATCH", "排班日期、人员、线体或站位不合法，单次最多安排93天", HttpStatus.BAD_REQUEST);
        }
    }

    private void requireScheduler(CurrentUser actor) {
        if (!actor.roles().contains(BusinessRoles.ASSISTANT_ENGINEER)
                && !actor.roles().contains(BusinessRoles.SUPERVISOR)) {
            throw new ApiException("FORBIDDEN", "仅助理工程师或主管可以编辑排班", HttpStatus.FORBIDDEN);
        }
    }

    private void requireAssignable(CurrentUser actor, UUID userId) {
        if (actor.roles().contains(BusinessRoles.SUPERVISOR)) return;
        UUID targetTeam = userTeam(userId);
        if (!actor.roles().contains(BusinessRoles.ASSISTANT_ENGINEER) || !actor.orgUnitId().equals(targetTeam)) {
            throw new ApiException("SCHEDULE_SCOPE_DENIED", "助理工程师只能安排自己和本班组技术员", HttpStatus.FORBIDDEN);
        }
        Set<String> targetRoles = new HashSet<>(jdbc.sql("SELECT role_code FROM role_binding WHERE user_id=:id")
                .param("id", userId).query(String.class).list());
        if (!actor.userId().equals(userId) && !targetRoles.contains(BusinessRoles.TECHNICIAN)) {
            throw new ApiException("SCHEDULE_SCOPE_DENIED", "助理工程师只能安排自己和本班组技术员", HttpStatus.FORBIDDEN);
        }
    }

    private Scope scope(CurrentUser actor) {
        if (actor.roles().contains(BusinessRoles.SUPERVISOR) || actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER)) return Scope.ALL;
        if (actor.roles().contains(BusinessRoles.ASSISTANT_ENGINEER)) return Scope.TEAM;
        return Scope.SELF;
    }

    private void ensureStationCapacity(LocalDate date, String shiftCode, UUID stationId, List<UUID> addingUsers) {
        List<UUID> existing = jdbc.sql("""
                SELECT DISTINCT user_id FROM schedule_assignment WHERE business_date=:date AND shift_code=:shiftCode
                  AND station_id=:stationId AND status='PUBLISHED'
                """).param("date", date).param("shiftCode", shiftCode).param("stationId", stationId).query(UUID.class).list();
        Set<UUID> combined = new HashSet<>(existing); combined.addAll(addingUsers);
        if (combined.size() > 2) throw new ApiException("STATION_CAPACITY_EXCEEDED", "同一班次同一站位最多安排两人", HttpStatus.CONFLICT);
    }

    private void requireLineStation(UUID lineId, List<UUID> stationIds) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM station WHERE line_id=:lineId AND id IN (:ids) AND status='ACTIVE'")
                .param("lineId", lineId).param("ids", stationIds).query(Long.class).single();
        if (count != stationIds.stream().distinct().count()) {
            throw new ApiException("INVALID_LINE_STATION", "线体或站位不存在、已停用或对应关系错误", HttpStatus.BAD_REQUEST);
        }
    }

    private UUID userTeam(UUID userId) {
        return jdbc.sql("SELECT org_unit_id FROM app_user WHERE id=:id AND status='ACTIVE'")
                .param("id", userId).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException("USER_NOT_FOUND", "有效人员不存在", HttpStatus.NOT_FOUND));
    }

    private ShiftRow shift(String code, LocalDate date) {
        return jdbc.sql("""
                SELECT id,code,name,starts_at,ends_at FROM shift_definition
                WHERE code=:code AND status='ACTIVE' AND effective_from<=:date
                  AND (effective_to IS NULL OR effective_to>=:date)
                ORDER BY effective_from DESC LIMIT 1
                """).param("code", code == null ? "" : code.toUpperCase()).param("date", date)
                .query((rs, n) -> new ShiftRow(rs.getObject("id", UUID.class),rs.getString("code"),rs.getString("name"),
                        rs.getObject("starts_at", LocalTime.class),rs.getObject("ends_at", LocalTime.class)))
                .optional().orElseThrow(() -> new ApiException("SHIFT_NOT_FOUND", "指定日期没有有效班次配置", HttpStatus.BAD_REQUEST));
    }

    private ShiftTimes times(LocalDate date, ShiftRow shift) {
        ZonedDateTime start = ZonedDateTime.of(date, shift.startsAt(), ZONE);
        LocalDate endDate = !shift.endsAt().isAfter(shift.startsAt()) ? date.plusDays(1) : date;
        return new ShiftTimes(start.toOffsetDateTime(), ZonedDateTime.of(endDate, shift.endsAt(), ZONE).toOffsetDateTime());
    }

    private String shiftName(String code) { return "NIGHT".equals(code) ? "夜班" : "白班"; }

    private AssignmentView get(UUID id) {
        return jdbc.sql(baseSelect() + " WHERE s.id=:id").param("id", id).query(this::map).optional()
                .orElseThrow(() -> new ApiException("SCHEDULE_NOT_FOUND", "排班不存在", HttpStatus.NOT_FOUND));
    }

    private String baseSelect() {
        return """
                SELECT s.id,s.user_id,u.employee_no,u.display_name,s.team_id,team.name team_name,
                       s.business_date,s.shift_code,s.shift_starts_at,s.shift_ends_at,s.line_id,l.name line_name,
                       s.station_id,st.name station_name,s.status,s.acknowledged_at,s.version,s.created_at,s.updated_at
                FROM schedule_assignment s JOIN app_user u ON u.id=s.user_id
                JOIN org_unit team ON team.id=s.team_id JOIN production_line l ON l.id=s.line_id
                JOIN station st ON st.id=s.station_id
                """;
    }

    private AssignmentView map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new AssignmentView(rs.getObject("id", UUID.class),rs.getObject("user_id", UUID.class),
                rs.getString("employee_no"),rs.getString("display_name"),rs.getObject("team_id", UUID.class),
                rs.getString("team_name"),rs.getObject("business_date", LocalDate.class),rs.getString("shift_code"),
                rs.getObject("shift_starts_at", OffsetDateTime.class),rs.getObject("shift_ends_at", OffsetDateTime.class),
                rs.getObject("line_id", UUID.class),rs.getString("line_name"),rs.getObject("station_id", UUID.class),
                rs.getString("station_name"),rs.getString("status"),rs.getObject("acknowledged_at", OffsetDateTime.class),
                rs.getLong("version"),rs.getObject("created_at", OffsetDateTime.class),rs.getObject("updated_at", OffsetDateTime.class));
    }

    private void recordRevision(UUID assignmentId, UUID actorId, String action, String reason, Object before, Object after) {
        jdbc.sql("""
                INSERT INTO schedule_revision(id,assignment_id,actor_id,action,reason,before_data,after_data)
                VALUES (:id,:assignmentId,:actorId,:action,:reason,CAST(:before AS jsonb),CAST(:after AS jsonb))
                """).param("id", UUID.randomUUID()).param("assignmentId", assignmentId).param("actorId", actorId)
                .param("action", action).param("reason", clean(reason)).param("before", json(before)).param("after", json(after)).update();
    }

    private void conflict() { throw new ApiException("VERSION_CONFLICT", "排班已被其他人修改，请刷新后重试", HttpStatus.CONFLICT); }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { return "{}"; } }

    private enum Scope { SELF, TEAM, ALL }
    private record ShiftRow(UUID id, String code, String name, LocalTime startsAt, LocalTime endsAt) {}
    private record ShiftTimes(OffsetDateTime startsAt, OffsetDateTime endsAt) {}
    public record BatchRequest(LocalDate from, LocalDate to, String shiftCode, List<UUID> userIds,
                               UUID lineId, List<UUID> stationIds, boolean publish, String reason) {}
    public record BatchResult(int created, int existing, List<UUID> assignmentIds) {}
    public record AssignmentView(UUID id, UUID userId, String employeeNo, String displayName, UUID teamId,
                                 String teamName, LocalDate businessDate, String shiftCode,
                                 OffsetDateTime shiftStartsAt, OffsetDateTime shiftEndsAt, UUID lineId,
                                 String lineName, UUID stationId, String stationName, String status,
                                 OffsetDateTime acknowledgedAt, long version, OffsetDateTime createdAt,
                                 OffsetDateTime updatedAt) {}
}
