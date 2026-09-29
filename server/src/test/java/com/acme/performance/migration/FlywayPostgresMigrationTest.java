package com.acme.performance.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayPostgresMigrationTest {

    @Test
    void appliesEveryMigrationToRealPostgres() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            var result = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .load()
                    .migrate();

            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(24);
            assertThat(tableExists(dataSource, "settlement_approver")).isTrue();
            assertThat(tableExists(dataSource, "score_event")).isTrue();
            assertThat(tableExists(dataSource, "settlement_run")).isTrue();
            assertThat(tableExists(dataSource, "grade_snapshot")).isTrue();
            assertThat(tableExists(dataSource, "schedule_assignment")).isTrue();
            assertThat(tableExists(dataSource, "equipment_incident")).isTrue();
            assertThat(tableExists(dataSource, "idempotency_request")).isTrue();
            assertThat(tableExists(dataSource, "incident_corrective_action")).isTrue();
            assertThat(tableExists(dataSource, "incident_archive_event")).isTrue();
            assertThat(tableExists(dataSource, "settlement_confirmation")).isTrue();
            assertThat(triggerExists(dataSource, "score_event_no_update")).isTrue();
        }
    }

    private boolean tableExists(DataSource dataSource, String tableName) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name=?)")) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private boolean triggerExists(DataSource dataSource, String triggerName) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname=?)")) {
            statement.setString(1, triggerName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
