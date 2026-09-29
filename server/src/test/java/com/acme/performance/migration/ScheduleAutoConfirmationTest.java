package com.acme.performance.migration;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.notification.service.NotificationService;
import com.acme.performance.schedule.service.ScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ScheduleAutoConfirmationTest {
    @Test
    void publicationAndLegacyMigrationDoNotRequireReadingOrAcknowledgement() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var ds = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(ds).target("19").load().migrate();
            var jdbc = JdbcClient.create(ds);
            UUID team=UUID.randomUUID(), user=UUID.randomUUID(), line=UUID.randomUUID(), station=UUID.randomUUID();
            jdbc.sql("INSERT INTO org_unit(id,type,name) VALUES (:id,'TEAM','auto-confirm-test')").param("id",team).update();
            jdbc.sql("INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status) VALUES (:id,'auto-confirm-test','test',:team,'ACTIVE')").param("id",user).param("team",team).update();
            jdbc.sql("INSERT INTO production_line(id,org_unit_id,code,name,status) VALUES (:id,:team,'test','test','ACTIVE')").param("id",line).param("team",team).update();
            jdbc.sql("INSERT INTO station(id,line_id,code,name,status) VALUES (:id,:line,'test','test','ACTIVE')").param("id",station).param("line",line).update();
            var notifications=mock(NotificationService.class);
            var service=new ScheduleService(jdbc,notifications,mock(AuditLogService.class),new ObjectMapper().findAndRegisterModules());
            var actor=new CurrentUser(user,"test","test",team,Set.of("SUPERVISOR"),false);
            LocalDate date=LocalDate.now().plusDays(5);
            UUID id=service.createBatch(actor,new ScheduleService.BatchRequest(date,date,"DAY",List.of(user),line,List.of(station),true,null),"test").assignmentIds().getFirst();
            assertThat(service.list(actor,date,date,null,null).getFirst().acknowledgedAt()).isNotNull();
            verify(notifications).create(eq(user),eq("SCHEDULE_PUBLISHED"),eq("新排班已发布"),anyString(),eq("SCHEDULE_ASSIGNMENT"),eq(id),eq(false));
            LocalDate next=date.plusDays(1);
            UUID draft=service.createBatch(actor,new ScheduleService.BatchRequest(next,next,"DAY",List.of(user),line,List.of(station),false,null),"test").assignmentIds().getFirst();
            assertThat(service.list(actor,next,next,null,null).getFirst().acknowledgedAt()).isNull();
            assertThat(service.publish(actor,draft,0,"test").acknowledgedAt()).isNotNull();
            service.cancel(actor,draft,1,"test","test");
            verify(notifications).create(eq(user),eq("SCHEDULE_CHANGED"),anyString(),anyString(),eq("SCHEDULE_ASSIGNMENT"),eq(draft),eq(false));
            // Simulate a pre-upgrade unconfirmed record and unread schedule message.
            jdbc.sql("UPDATE schedule_assignment SET acknowledged_at=NULL WHERE id=:id").param("id",id).update();
            jdbc.sql("INSERT INTO notification(id,user_id,type,title,content,source_type,source_id,requires_acknowledgement) VALUES (:nid,:user,'SCHEDULE_PUBLISHED','新排班待确认','请确认。','SCHEDULE_ASSIGNMENT',:id,TRUE)").param("nid",UUID.randomUUID()).param("user",user).param("id",id).update();
            Flyway.configure().dataSource(ds).load().migrate();
            assertThat(service.list(actor,date,date,null,null).getFirst().acknowledgedAt()).isNotNull();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM notification WHERE user_id=:user AND requires_acknowledgement=FALSE AND read_at IS NULL AND acknowledged_at IS NULL AND title='新排班已发布'").param("user",user).query(Long.class).single()).isEqualTo(1);
        }
    }
}
