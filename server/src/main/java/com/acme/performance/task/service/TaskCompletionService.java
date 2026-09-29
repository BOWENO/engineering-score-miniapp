package com.acme.performance.task.service;

import com.acme.performance.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;

@Service
public class TaskCompletionService {
    private final JdbcClient jdbc;

    public TaskCompletionService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public CompletionResult complete(UUID actorId, UUID taskId, String idempotencyKey, long version,
                                     String submittedValue, String description) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ApiException("IDEMPOTENCY_KEY_REQUIRED", "必须提供有效的Idempotency-Key", HttpStatus.BAD_REQUEST);
        }
        var existing = jdbc.sql("SELECT id FROM task_submission WHERE user_id=:userId AND idempotency_key=:key")
                .param("userId", actorId).param("key", idempotencyKey).query(UUID.class).optional();
        if (existing.isPresent()) return new CompletionResult(existing.get(), true);

        TaskRow task = jdbc.sql("""
                SELECT t.id,t.user_id,t.biz_date,t.rule_code,t.status,t.version,t.deadline_at,r.score
                FROM daily_task t JOIN score_rule r ON r.code=t.rule_code AND r.rule_version_id=t.rule_version_id
                WHERE t.id=:taskId
                """).param("taskId", taskId).query((rs, rowNum) -> new TaskRow(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("biz_date", LocalDate.class), rs.getString("rule_code"),
                        rs.getString("status"), rs.getLong("version"),
                        rs.getObject("deadline_at", OffsetDateTime.class), rs.getBigDecimal("score"))).optional()
                .orElseThrow(() -> new ApiException("TASK_NOT_FOUND", "任务不存在或规则未生效", HttpStatus.NOT_FOUND));
        if (!task.userId().equals(actorId)) throw new ApiException("FORBIDDEN", "无权提交该任务", HttpStatus.FORBIDDEN);
        if (!"PENDING".equals(task.status())) throw new ApiException("TASK_ALREADY_COMPLETED", "任务已完成", HttpStatus.CONFLICT);
        if (task.version() != version) throw new ApiException("VERSION_CONFLICT", "任务已被其他操作更新", HttpStatus.CONFLICT);
        if (task.deadlineAt() != null && OffsetDateTime.now().isAfter(task.deadlineAt())) {
            throw new ApiException("TASK_EXPIRED", "任务已超过提交时限", HttpStatus.CONFLICT);
        }

        UUID submissionId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO task_submission(id,task_id,user_id,submitted_value,description,idempotency_key)
                VALUES (:id,:taskId,:userId,:value,:description,:key)
                ON CONFLICT DO NOTHING
                """).param("id", submissionId).param("taskId", taskId).param("userId", actorId)
                .param("value", submittedValue).param("description", description).param("key", idempotencyKey).update();
        if (inserted == 0) {
            return jdbc.sql("SELECT id FROM task_submission WHERE user_id=:userId AND idempotency_key=:key")
                    .param("userId", actorId).param("key", idempotencyKey).query(UUID.class).optional()
                    .map(id -> new CompletionResult(id, true))
                    .orElseThrow(() -> new ApiException("TASK_ALREADY_COMPLETED", "任务已由其他请求完成", HttpStatus.CONFLICT));
        }
        jdbc.sql("INSERT INTO validation_result(id,submission_id,valid,code,message) VALUES (:id,:submissionId,true,'PASSED','校验通过')")
                .param("id", UUID.randomUUID()).param("submissionId", submissionId).update();
        int updated = jdbc.sql("UPDATE daily_task SET status='COMPLETED',version=version+1 WHERE id=:id AND version=:version AND status='PENDING'")
                .param("id", taskId).param("version", version).update();
        if (updated != 1) throw new ApiException("VERSION_CONFLICT", "任务已被其他操作更新", HttpStatus.CONFLICT);

        UUID eventId = UUID.randomUUID();
        BigDecimal score = task.score() == null ? BigDecimal.ZERO : task.score();
        jdbc.sql("""
                INSERT INTO score_event(id,user_id,rule_code,biz_date,original_score,actual_score,source,source_id)
                VALUES (:id,:userId,:ruleCode,:bizDate,:score,:score,'DAILY_TASK',:sourceId)
                """).param("id", eventId).param("userId", actorId).param("ruleCode", task.ruleCode())
                .param("bizDate", task.businessDate()).param("score", score).param("sourceId", submissionId).update();
        String period = YearMonth.from(task.businessDate()).toString();
        jdbc.sql("""
                INSERT INTO score_summary(user_id,period,base,bonus,penalty,total)
                VALUES (:userId,:period,:score,0,0,:score)
                ON CONFLICT (user_id,period) DO UPDATE SET
                  base=score_summary.base+EXCLUDED.base,total=score_summary.total+EXCLUDED.total,rebuilt_at=CURRENT_TIMESTAMP
                """).param("userId", actorId).param("period", period).param("score", score).update();
        return new CompletionResult(submissionId, false);
    }

    public record CompletionResult(UUID submissionId, boolean replayed) {}
    private record TaskRow(UUID id, UUID userId, LocalDate businessDate, String ruleCode, String status,
                           long version, OffsetDateTime deadlineAt, BigDecimal score) {}
}
