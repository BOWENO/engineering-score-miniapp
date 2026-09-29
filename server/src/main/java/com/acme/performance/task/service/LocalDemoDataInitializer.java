package com.acme.performance.task.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@Profile("local")
@Component
public class LocalDemoDataInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;

    public LocalDemoDataInitializer(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long published = jdbc.sql("SELECT COUNT(*) FROM rule_version WHERE status='PUBLISHED'").query(Long.class).single();
        if (published > 0) return;
        UUID versionId = UUID.randomUUID();
        jdbc.sql("INSERT INTO rule_version(id,version,status,effective_at) VALUES (:id,'LOCAL-1','PUBLISHED',CURRENT_TIMESTAMP)")
                .param("id", versionId).update();
        jdbc.sql("""
                INSERT INTO score_rule(id,rule_version_id,code,title,type,score,cap_policy,evidence_schema,approval_flow,effective_from)
                VALUES (:id,:versionId,'B01','完成今日基础任务','BASE',1,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,:effectiveFrom)
                """).param("id", UUID.randomUUID()).param("versionId", versionId)
                .param("effectiveFrom", LocalDate.now(ZoneId.of("Asia/Shanghai"))).update();
        jdbc.sql("""
                INSERT INTO score_rule(id,rule_version_id,code,title,type,score,cap_policy,evidence_schema,approval_flow,effective_from)
                VALUES (:id,:versionId,'P01','主动发现并解决问题','BONUS',5,'{\"monthlyCap\":20}'::jsonb,
                        '{\"attachmentRequired\":true}'::jsonb,'{\"firstNode\":\"SUPERVISOR\"}'::jsonb,:effectiveFrom)
                """).param("id", UUID.randomUUID()).param("versionId", versionId)
                .param("effectiveFrom", LocalDate.now(ZoneId.of("Asia/Shanghai"))).update();
    }
}
