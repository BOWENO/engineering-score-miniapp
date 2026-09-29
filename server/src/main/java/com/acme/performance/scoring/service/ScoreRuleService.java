package com.acme.performance.scoring.service;

import com.acme.performance.common.api.ApiException;
import com.acme.performance.scoring.model.ScoreRuleView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class ScoreRuleService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ScoreRuleService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ScoreRuleView> activeRules(String type) {
        return activeRules(type,LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }

    public List<ScoreRuleView> activeRules(String type,LocalDate today) {
        String normalizedType = type == null || type.isBlank() ? null : type.toUpperCase();
        return jdbc.sql("""
                SELECT r.id,r.code,r.title,r.type,r.score,r.cap_policy::text,r.evidence_schema::text,
                       r.approval_flow::text,r.effective_from,r.effective_to
                FROM score_rule r JOIN rule_version rv ON rv.id=r.rule_version_id
                WHERE rv.id=(SELECT id FROM rule_version WHERE status IN ('PUBLISHED','RETIRED') AND effective_at<:until ORDER BY effective_at DESC,created_at DESC LIMIT 1) AND r.effective_from<=:today
                  AND (r.effective_to IS NULL OR r.effective_to>=:today)
                  AND (:type IS NULL OR r.type=:type)
                ORDER BY r.type,r.code
                """).param("until",today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime()).param("today", today).param("type", normalizedType, java.sql.Types.VARCHAR)
                .query((rs, rowNum) -> new ScoreRuleView(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("title"), rs.getString("type"), rs.getBigDecimal("score"),
                        json(rs.getString("cap_policy")), json(rs.getString("evidence_schema")),
                        json(rs.getString("approval_flow")), rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class))).list();
    }

    private JsonNode json(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException ex) {
            throw new ApiException("INVALID_RULE_CONFIGURATION", "积分规则配置无法解析", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
