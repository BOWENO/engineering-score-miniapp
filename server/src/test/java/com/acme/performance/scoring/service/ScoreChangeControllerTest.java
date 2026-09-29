package com.acme.performance.scoring.service;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ScoreChangeControllerTest {
    @Test void committedChangesWakeAllListenersButRollbackDoesNot() throws Exception {
        try(var pg=EmbeddedPostgres.builder().start()){
            var ds=pg.getPostgresDatabase();Flyway.configure().dataSource(ds).load().migrate();
            var service=new ScoreChangeController(JdbcClient.create(ds));
            var request=new MockHttpServletRequest();
            String original=service.revision();
            assertThat(service.watch("",request).hasResult()).isTrue();
            var a=service.watch(original,request);var b=service.watch(original,request);
            service.deliver();assertThat(a.hasResult()).isFalse();
            try(var connection=ds.getConnection()){
                connection.setAutoCommit(false);
                try(var statement=connection.createStatement()){
                    // A statement trigger also runs for zero matching rows; no business fixture needed.
                    statement.executeUpdate("UPDATE score_summary SET total=total WHERE false");
                    service.deliver();assertThat(a.hasResult()).isFalse();
                    connection.rollback();
                    assertThat(service.revision()).isEqualTo(original);
                    for(String table:new String[]{"score_summary","performance_case","performance_appeal","shift_score","d_grade_nomination","grade_snapshot"}){
                        statement.executeUpdate("DELETE FROM "+table+" WHERE false");
                        connection.commit();
                        assertThat(service.revision()).isNotEqualTo(original);original=service.revision();
                    }
                }
            }
            service.deliver();assertThat(a.hasResult()).isTrue();assertThat(b.hasResult()).isTrue();
            assertThat(service.watch("old-server-version",request).hasResult()).isTrue();
        }
    }
}
