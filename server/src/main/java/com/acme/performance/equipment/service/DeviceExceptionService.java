package com.acme.performance.equipment.service;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.organization.service.DataScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DeviceExceptionService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> REVIEWER_ROLES = Set.of("SUPERVISOR");
    private final JdbcClient jdbc;
    private final DataScopeService dataScope;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public DeviceExceptionService(JdbcClient jdbc, DataScopeService dataScope, AuditLogService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.dataScope = dataScope;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional
    public ExceptionView create(CurrentUser actor, String idempotencyKey, UUID equipmentId, LocalDate occurredOn,
                                String phenomenon, String handlingMethod, String rootCause,
                                String longTermAction, boolean submit) {
        requireIdempotencyKey(idempotencyKey);
        validate(equipmentId, phenomenon, handlingMethod, rootCause);
        var existing = jdbc.sql("SELECT id FROM device_exception WHERE reporter_id=:reporterId AND idempotency_key=:key")
                .param("reporterId", actor.userId()).param("key", idempotencyKey).query(UUID.class).optional();
        if (existing.isPresent()) return getForUser(existing.get(), actor.userId());
        requireActiveEquipment(equipmentId);
        UUID id = UUID.randomUUID();
        String status = submit ? "SUBMITTED" : "DRAFT";
        jdbc.sql("""
                INSERT INTO device_exception(id,equipment_id,reporter_id,occurred_on,phenomenon,handling_method,
                  root_cause,long_term_action,status,idempotency_key,submitted_at)
                VALUES (:id,:equipmentId,:reporterId,:occurredOn,:phenomenon,:handlingMethod,:rootCause,
                  :longTermAction,:status,:key,CASE WHEN :submit THEN CURRENT_TIMESTAMP ELSE NULL END)
                """).param("id", id).param("equipmentId", equipmentId).param("reporterId", actor.userId())
                .param("occurredOn", occurredOn == null ? LocalDate.now(BUSINESS_ZONE) : occurredOn)
                .param("phenomenon", phenomenon.trim()).param("handlingMethod", handlingMethod.trim())
                .param("rootCause", rootCause.trim()).param("longTermAction", clean(longTermAction))
                .param("status", status).param("key", idempotencyKey).param("submit", submit).update();
        return getForUser(id, actor.userId());
    }

    @Transactional
    public ExceptionView update(CurrentUser actor, UUID id, long version, UUID equipmentId, LocalDate occurredOn,
                                String phenomenon, String handlingMethod, String rootCause,
                                String longTermAction, boolean submit) {
        validate(equipmentId, phenomenon, handlingMethod, rootCause);
        requireActiveEquipment(equipmentId);
        ExceptionView current = getForUser(id, actor.userId());
        if (!Set.of("DRAFT", "RETURNED").contains(current.status())) {
            throw new ApiException("INVALID_EXCEPTION_STATUS", "当前异常记录不能修改", HttpStatus.CONFLICT);
        }
        if (current.version() != version) throw new ApiException("VERSION_CONFLICT", "异常记录已被其他操作更新", HttpStatus.CONFLICT);
        String status = submit ? "SUBMITTED" : "DRAFT";
        int updated = jdbc.sql("""
                UPDATE device_exception SET equipment_id=:equipmentId,occurred_on=:occurredOn,phenomenon=:phenomenon,
                  handling_method=:handlingMethod,root_cause=:rootCause,long_term_action=:longTermAction,status=:status,
                  submitted_at=CASE WHEN :submit THEN CURRENT_TIMESTAMP ELSE submitted_at END,
                  reviewer_id=NULL,review_comment=NULL,reviewed_at=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND reporter_id=:reporterId AND version=:version AND status IN ('DRAFT','RETURNED')
                """).param("equipmentId", equipmentId)
                .param("occurredOn", occurredOn == null ? LocalDate.now(BUSINESS_ZONE) : occurredOn)
                .param("phenomenon", phenomenon.trim()).param("handlingMethod", handlingMethod.trim())
                .param("rootCause", rootCause.trim()).param("longTermAction", clean(longTermAction))
                .param("status", status).param("submit", submit).param("id", id)
                .param("reporterId", actor.userId()).param("version", version).update();
        if (updated != 1) throw new ApiException("VERSION_CONFLICT", "异常记录已被其他操作更新", HttpStatus.CONFLICT);
        return getForUser(id, actor.userId());
    }

    @Transactional(readOnly = true)
    public List<ExceptionView> mine(CurrentUser actor) {
        return query("WHERE x.reporter_id=:reporterId", actor.userId(), null, null, null, null, 200);
    }

    @Transactional(readOnly = true)
    public List<ExceptionView> pending(CurrentUser actor) {
        requireReviewer(actor);
        return query("WHERE x.status='SUBMITTED'", null, null, null, null, null, 500).stream()
                .filter(item -> dataScope.canAccessUser(actor, item.reporterId())).toList();
    }

    @Transactional
    public ExceptionView review(CurrentUser actor, UUID id, long version, String action, String comment, String requestId) {
        requireReviewer(actor);
        String normalized = action == null ? "" : action.toUpperCase();
        if (!Set.of("APPROVE", "RETURN").contains(normalized)) {
            throw new ApiException("INVALID_REVIEW_ACTION", "审核动作只能为通过或退回", HttpStatus.BAD_REQUEST);
        }
        if ("RETURN".equals(normalized) && (comment == null || comment.isBlank())) {
            throw new ApiException("REVIEW_COMMENT_REQUIRED", "退回时必须填写原因", HttpStatus.BAD_REQUEST);
        }
        ExceptionView current = get(id);
        if (actor.userId().equals(current.reporterId())) {
            throw new ApiException("CANNOT_REVIEW_SELF", "不能审核本人提交的异常", HttpStatus.FORBIDDEN);
        }
        if (!dataScope.canAccessUser(actor, current.reporterId())) {
            throw new ApiException("FORBIDDEN", "无权审核该异常记录", HttpStatus.FORBIDDEN);
        }
        if (!"SUBMITTED".equals(current.status())) throw new ApiException("EXCEPTION_ALREADY_REVIEWED", "异常记录已处理", HttpStatus.CONFLICT);
        if (current.version() != version) throw new ApiException("VERSION_CONFLICT", "异常记录已被其他操作更新", HttpStatus.CONFLICT);
        String target = "APPROVE".equals(normalized) ? "ARCHIVED" : "RETURNED";
        int updated = jdbc.sql("""
                UPDATE device_exception SET status=:status,reviewer_id=:reviewerId,review_comment=:comment,
                  reviewed_at=CURRENT_TIMESTAMP,version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND version=:version AND status='SUBMITTED'
                """).param("status", target).param("reviewerId", actor.userId()).param("comment", clean(comment))
                .param("id", id).param("version", version).update();
        if (updated != 1) throw new ApiException("VERSION_CONFLICT", "异常记录已被其他操作更新", HttpStatus.CONFLICT);
        ExceptionView result = get(id);
        audit.record(actor.userId(), "DEVICE_EXCEPTION_" + normalized, "DEVICE_EXCEPTION", id, json(result), requestId);
        return result;
    }

    @Transactional(readOnly = true)
    public List<ExceptionView> archive(CurrentUser actor, List<UUID> deviceIds, LocalDate from,
                                       LocalDate to, String keyword, int limit) {
        validateDateRange(from, to);
        List<ExceptionView> results = query("WHERE x.status='ARCHIVED'", null, deviceIds, from, to, keyword,
                Math.max(1, Math.min(limit, 1000)));
        return results.stream().filter(item -> canReadArchive(actor, item)).toList();
    }

    @Transactional(readOnly = true)
    public List<ExceptionView> exportRows(CurrentUser actor, List<UUID> deviceIds, LocalDate from, LocalDate to) {
        validateDateRange(from, to);
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw new ApiException("DEVICE_REQUIRED", "请至少选择一台设备导出", HttpStatus.BAD_REQUEST);
        }
        return query("WHERE x.status='ARCHIVED'", null, deviceIds, from, to, null, 10000).stream()
                .filter(item -> canReadArchive(actor, item)).toList();
    }

    private List<ExceptionView> query(String baseWhere, UUID reporterId, List<UUID> deviceIds,
                                      LocalDate from, LocalDate to, String keyword, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT x.id,x.equipment_id,e.code AS equipment_code,e.name AS equipment_name,e.category,
                       e.org_unit_id,eorg.name AS equipment_org_name,x.reporter_id,u.employee_no,u.display_name,
                       u.org_unit_id AS reporter_org_id,x.occurred_on,x.phenomenon,x.handling_method,x.root_cause,
                       x.long_term_action,x.status,x.reviewer_id,reviewer.display_name AS reviewer_name,
                       x.review_comment,x.submitted_at,x.reviewed_at,x.version,x.created_at
                FROM device_exception x JOIN equipment e ON e.id=x.equipment_id
                JOIN org_unit eorg ON eorg.id=e.org_unit_id JOIN app_user u ON u.id=x.reporter_id
                LEFT JOIN app_user reviewer ON reviewer.id=x.reviewer_id
                """).append(baseWhere);
        if (deviceIds != null && !deviceIds.isEmpty()) sql.append(" AND x.equipment_id IN (:deviceIds)");
        if (from != null) sql.append(" AND x.occurred_on>=:fromDate");
        if (to != null) sql.append(" AND x.occurred_on<=:toDate");
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (x.phenomenon ILIKE :keyword OR x.handling_method ILIKE :keyword OR x.root_cause ILIKE :keyword OR COALESCE(x.long_term_action,'') ILIKE :keyword)");
        }
        sql.append(" ORDER BY x.occurred_on DESC,x.created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString());
        if (reporterId != null) spec.param("reporterId", reporterId);
        if (deviceIds != null && !deviceIds.isEmpty()) spec.param("deviceIds", deviceIds);
        if (from != null) spec.param("fromDate", from);
        if (to != null) spec.param("toDate", to);
        if (keyword != null && !keyword.isBlank()) spec.param("keyword", "%" + keyword.trim() + "%");
        spec.param("limit", limit);
        return spec.query((rs, n) -> new ExceptionView(
                rs.getObject("id", UUID.class), rs.getObject("equipment_id", UUID.class), rs.getString("equipment_code"),
                rs.getString("equipment_name"), rs.getString("category"), rs.getObject("org_unit_id", UUID.class),
                rs.getString("equipment_org_name"), rs.getObject("reporter_id", UUID.class), rs.getString("employee_no"),
                rs.getString("display_name"), rs.getObject("reporter_org_id", UUID.class),
                rs.getObject("occurred_on", LocalDate.class), rs.getString("phenomenon"),
                rs.getString("handling_method"), rs.getString("root_cause"), rs.getString("long_term_action"),
                rs.getString("status"), rs.getObject("reviewer_id", UUID.class), rs.getString("reviewer_name"),
                rs.getString("review_comment"), rs.getObject("submitted_at", OffsetDateTime.class),
                rs.getObject("reviewed_at", OffsetDateTime.class), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private ExceptionView getForUser(UUID id, UUID reporterId) {
        ExceptionView result = get(id);
        if (!result.reporterId().equals(reporterId)) throw new ApiException("DEVICE_EXCEPTION_NOT_FOUND", "异常记录不存在", HttpStatus.NOT_FOUND);
        return result;
    }

    private ExceptionView get(UUID id) {
        return query("WHERE x.id=:exceptionId", null, null, null, null, null, 1, id).stream().findFirst()
                .orElseThrow(() -> new ApiException("DEVICE_EXCEPTION_NOT_FOUND", "异常记录不存在", HttpStatus.NOT_FOUND));
    }

    private List<ExceptionView> query(String baseWhere, UUID reporterId, List<UUID> deviceIds,
                                      LocalDate from, LocalDate to, String keyword, int limit, UUID exceptionId) {
        String sql = """
                SELECT x.id,x.equipment_id,e.code AS equipment_code,e.name AS equipment_name,e.category,
                       e.org_unit_id,eorg.name AS equipment_org_name,x.reporter_id,u.employee_no,u.display_name,
                       u.org_unit_id AS reporter_org_id,x.occurred_on,x.phenomenon,x.handling_method,x.root_cause,
                       x.long_term_action,x.status,x.reviewer_id,reviewer.display_name AS reviewer_name,
                       x.review_comment,x.submitted_at,x.reviewed_at,x.version,x.created_at
                FROM device_exception x JOIN equipment e ON e.id=x.equipment_id
                JOIN org_unit eorg ON eorg.id=e.org_unit_id JOIN app_user u ON u.id=x.reporter_id
                LEFT JOIN app_user reviewer ON reviewer.id=x.reviewer_id
                """ + baseWhere + " ORDER BY x.created_at DESC LIMIT :limit";
        return jdbc.sql(sql).param("exceptionId", exceptionId).param("limit", limit)
                .query((rs, n) -> new ExceptionView(rs.getObject("id", UUID.class), rs.getObject("equipment_id", UUID.class),
                        rs.getString("equipment_code"), rs.getString("equipment_name"), rs.getString("category"),
                        rs.getObject("org_unit_id", UUID.class), rs.getString("equipment_org_name"),
                        rs.getObject("reporter_id", UUID.class), rs.getString("employee_no"), rs.getString("display_name"),
                        rs.getObject("reporter_org_id", UUID.class), rs.getObject("occurred_on", LocalDate.class),
                        rs.getString("phenomenon"), rs.getString("handling_method"), rs.getString("root_cause"),
                        rs.getString("long_term_action"), rs.getString("status"), rs.getObject("reviewer_id", UUID.class),
                        rs.getString("reviewer_name"), rs.getString("review_comment"),
                        rs.getObject("submitted_at", OffsetDateTime.class), rs.getObject("reviewed_at", OffsetDateTime.class),
                        rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    private boolean canReadArchive(CurrentUser actor, ExceptionView item) {
        if (actor.administrator() || actor.orgUnitId().equals(item.reporterOrgId())) return true;
        return actor.roles().stream().anyMatch(Set.of("ASSISTANT_ENGINEER", "SUPERVISOR", "DEPARTMENT_MANAGER")::contains)
                && dataScope.canAccessUser(actor, item.reporterId());
    }

    private void requireReviewer(CurrentUser actor) {
        if (actor.roles().stream().noneMatch(REVIEWER_ROLES::contains)) {
            throw new ApiException("FORBIDDEN", "仅主管可以审核设备异常", HttpStatus.FORBIDDEN);
        }
    }

    private void requireActiveEquipment(UUID id) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM equipment WHERE id=:id AND status='ACTIVE'")
                .param("id", id).query(Long.class).single();
        if (count != 1) throw new ApiException("EQUIPMENT_NOT_AVAILABLE", "设备不存在或已停用", HttpStatus.BAD_REQUEST);
    }

    private void validate(UUID equipmentId, String phenomenon, String handlingMethod, String rootCause) {
        if (equipmentId == null || phenomenon == null || phenomenon.isBlank()
                || handlingMethod == null || handlingMethod.isBlank() || rootCause == null || rootCause.isBlank()) {
            throw new ApiException("INVALID_DEVICE_EXCEPTION", "发生设备、异常现象、处理方法和问题根因均为必填项", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException("INVALID_DATE_RANGE", "开始日期不能晚于结束日期", HttpStatus.BAD_REQUEST);
        }
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ApiException("IDEMPOTENCY_KEY_REQUIRED", "必须提供有效的Idempotency-Key", HttpStatus.BAD_REQUEST);
        }
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { return "{}"; } }

    public record ExceptionView(UUID id, UUID equipmentId, String equipmentCode, String equipmentName,
                                String category, UUID equipmentOrgId, String equipmentOrgName,
                                UUID reporterId, String employeeNo, String reporterName, UUID reporterOrgId,
                                LocalDate occurredOn, String phenomenon, String handlingMethod, String rootCause,
                                String longTermAction, String status, UUID reviewerId, String reviewerName,
                                String reviewComment, OffsetDateTime submittedAt, OffsetDateTime reviewedAt,
                                long version, OffsetDateTime createdAt) {}
}
