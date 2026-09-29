package com.acme.performance.migration;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.incident.service.IncidentService;
import com.acme.performance.performance.service.PerformanceAnalyticsService;
import com.acme.performance.performance.service.PerformanceCaseService;
import com.acme.performance.common.service.BusinessDeadlineService;
import com.acme.performance.notification.service.NotificationService;
import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.common.api.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import com.acme.performance.auth.service.TokenService;
import com.acme.performance.auth.web.AuthenticationFilter;
import com.acme.performance.auth.web.CurrentUserArgumentResolver;
import com.acme.performance.common.web.GlobalExceptionHandler;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.common.idempotency.IdempotencyFilter;
import com.acme.performance.common.idempotency.IdempotencyService;
import com.acme.performance.performance.web.PerformanceCaseController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.BadSqlGrammarException;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

// Regression coverage in isolated PostgreSQL; never touches production data.
class RoleDiagnosticTest {
    @Test
    void verifiesRoleWorkflowsWithoutProductionData() throws Exception {
        try(var pg=EmbeddedPostgres.builder().start()) {
            var ds=pg.getPostgresDatabase();
            Flyway.configure().dataSource(ds).load().migrate();
            var jdbc=JdbcClient.create(ds);
            UUID team=UUID.randomUUID(),user=UUID.randomUUID();
            jdbc.sql("INSERT INTO org_unit(id,type,name) VALUES (:id,'TEAM','diagnostic')").param("id",team).update();
            jdbc.sql("INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status) VALUES (:id,'diagnostic','diagnostic',:team,'ACTIVE')").param("id",user).param("team",team).update();
            jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:user,'TECHNICIAN',:team)").param("id",UUID.randomUUID()).param("user",user).param("team",team).update();
            jdbc.sql("INSERT INTO score_summary(user_id,period,base,bonus,penalty,total) VALUES (:id,'2026-09',0,0,5,-5)").param("id",user).update();
            var analytics=new PerformanceAnalyticsService(jdbc);
            var technician=new CurrentUser(user,"diagnostic","diagnostic",team,Set.of("TECHNICIAN"),false);
            assertThat(jdbc.sql("SELECT total FROM score_summary WHERE user_id=:id").param("id",user).query(Integer.class).single()).isEqualTo(-5);
            assertThat(analytics.mine(technician,"2026-09").total()).isEqualByComparingTo("-5");
            for(String role: new String[]{"SUPERVISOR","DEPARTMENT_MANAGER"}) {
                var actor=new CurrentUser(user,"diagnostic","diagnostic",team,Set.of(role),false);
                assertThat(analytics.overview(actor,"2026-09").people()).anyMatch(p->p.userId().equals(user));
            }
            jdbc.sql("INSERT INTO shift_score(id,user_id,business_date,shift_code,shift_ends_at,status) VALUES (:id,:user,'2026-09-01','DAY',CURRENT_TIMESTAMP,'POSTED')").param("id",UUID.randomUUID()).param("user",user).update();
            assertThat(analytics.mine(technician,"2026-09").total()).isEqualByComparingTo("-5");
            var incidents=new IncidentService(jdbc,null,null,null,null);
            assertThat(incidents.minePending(technician)).isEmpty();
            var deadlines=mock(BusinessDeadlineService.class);
            when(deadlines.addHours(any(),anyInt(),any())).thenReturn(OffsetDateTime.now().plusDays(1));
            var cases=new PerformanceCaseService(jdbc,mock(NotificationService.class),deadlines,mock(AuditLogService.class));
            var input=new PerformanceCaseService.DeductionInput("SPECIAL_DEDUCTION",user,OffsetDateTime.parse("2026-09-02T10:00:00+08:00"),"isolated diagnostic","NONEXISTENT_DIAGNOSTIC_RULE",7,List.of(),null);
            assertThatThrownBy(()->cases.createDeduction(technician,input,"test")).isInstanceOf(ApiException.class);
            var supervisor=new CurrentUser(user,"diagnostic","diagnostic",team,Set.of("SUPERVISOR"),false);
            assertThatThrownBy(()->cases.createDeduction(supervisor,input,"test")).isInstanceOf(ApiException.class).hasMessageContaining("规则");
            UUID ruleVersion=UUID.randomUUID();
            jdbc.sql("INSERT INTO rule_version(id,version,status,effective_at) VALUES (:id,'REGRESSION','PUBLISHED','2020-01-01')").param("id",ruleVersion).update();
            for(String type:List.of("PENALTY","BONUS"))jdbc.sql("INSERT INTO score_rule(id,rule_version_id,code,title,type,score,effective_from) VALUES (:id,:version,:code,'回归规则',:type,10,'2020-01-01')").param("id",UUID.randomUUID()).param("version",ruleVersion).param("code",type.equals("BONUS")?"BONUS_TEST":"DIAGNOSTIC").param("type",type).update();
            var validInput=new PerformanceCaseService.DeductionInput("SPECIAL_DEDUCTION",user,input.occurredAt(),input.description(),"DIAGNOSTIC",7,List.of(),null);
            var deduction=cases.createDeduction(supervisor,validInput,"test");
            assertThat(cases.records(supervisor,"2026-09")).anyMatch(c->c.id().equals(deduction.id()));
            assertThat(deduction.status()).isEqualTo("EFFECTIVE");
            assertThat(deduction.effectiveScore()).isEqualTo(7);
            assertThat(jdbc.sql("SELECT total FROM score_summary WHERE user_id=:id").param("id",user).query(Integer.class).single()).isEqualTo(-12);
            assertThat(jdbc.sql("SELECT actual_score FROM score_event WHERE source_id=:id").param("id",deduction.id()).query(Integer.class).single()).isEqualTo(-7);
            assertThat(cases.mine(technician)).anyMatch(c->c.id().equals(deduction.id()));
            assertThat(cases.pending(supervisor)).noneMatch(c->c.id().equals(deduction.id()));
            assertThatThrownBy(()->cases.review(supervisor,deduction.id(),deduction.version(),"APPROVE",7,"NONEXISTENT_DIAGNOSTIC_RULE",null,"test")).isInstanceOf(ApiException.class);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM score_event WHERE source_id=:id").param("id",deduction.id()).query(Long.class).single()).isEqualTo(1);
            // Supplement: real transaction proxy and servlet request/filter chain.
            var proxy=new TransactionProxyFactoryBean();
            proxy.setTarget(cases);proxy.setProxyTargetClass(true);proxy.setTransactionManager(new DataSourceTransactionManager(ds));
            var attrs=new Properties();attrs.setProperty("*","PROPAGATION_REQUIRED");proxy.setTransactionAttributes(attrs);proxy.afterPropertiesSet();
            var transactionalCases=(PerformanceCaseService)proxy.getObject();
            var tokenService=mock(TokenService.class);
            when(tokenService.authenticate(any())).thenReturn(Optional.empty());
            when(tokenService.authenticate("tech")).thenReturn(Optional.of(technician));
            when(tokenService.authenticate("supervisor")).thenReturn(Optional.of(supervisor));
            var mapper=new ObjectMapper().findAndRegisterModules();
            var mvc=MockMvcBuilders.standaloneSetup(new PerformanceCaseController(transactionalCases))
                .setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addFilters(new RequestIdFilter(),new AuthenticationFilter(tokenService,mapper),new IdempotencyFilter(new IdempotencyService(jdbc),mapper)).build();
            mvc.perform(get("/api/performance-cases/mine")).andExpect(status().isUnauthorized());
            String body=mapper.writeValueAsString(Map.of("caseType","SPECIAL_DEDUCTION","targetUserId",user,"occurredAt","2026-09-02T10:00:00+08:00","description","HTTP isolated test","ruleCode","DIAGNOSTIC","score",3,"attachmentIds",List.of()));
            mvc.perform(post("/api/performance-cases/deductions").header("Authorization","Bearer supervisor").contentType("application/json").content(body)).andExpect(status().isBadRequest());
            mvc.perform(post("/api/performance-cases/deductions").header("Authorization","Bearer tech").header("Idempotency-Key","forbidden").contentType("application/json").content(body)).andExpect(status().isForbidden());
            var first=mvc.perform(post("/api/performance-cases/deductions").header("Authorization","Bearer supervisor").header("Idempotency-Key","repeat").contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
            String created=mapper.readTree(first.getResponse().getContentAsString()).path("data").path("id").asText();
            mvc.perform(post("/api/performance-cases/deductions").header("Authorization","Bearer supervisor").header("X-Idempotency-Key","repeat").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed","true"));
            assertThat(jdbc.sql("SELECT COUNT(*) FROM score_event WHERE source_id=:id").param("id",UUID.fromString(created)).query(Long.class).single()).isEqualTo(1);
            // Changed payload must conflict, not replay.
            mvc.perform(post("/api/performance-cases/deductions").header("Authorization","Bearer supervisor").header("Idempotency-Key","repeat").contentType("application/json").content(body.replace("HTTP isolated test","different payload"))).andExpect(status().isConflict());
            mvc.perform(post("/api/performance-cases/admonitions").header("Authorization","Bearer supervisor").header("Idempotency-Key","repeat").contentType("application/json").content(body)).andExpect(status().isConflict());
            mvc.perform(post("/api/performance-cases/deductions").header("Authorization","Bearer supervisor").header("Idempotency-Key","invalid").contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
            // Malformed JSON is a client error.
            mvc.perform(post("/api/performance-cases/deductions").header("Authorization","Bearer supervisor").header("Idempotency-Key","broken-json").contentType("application/json").content("{" )).andExpect(status().isBadRequest());
            // Full two-level bonus review + appeal reversal, with separate actors.
            UUID assistantId=seed(jdbc,team,"assistant","ASSISTANT_ENGINEER"), otherId=seed(jdbc,team,"second","SUPERVISOR"), thirdId=seed(jdbc,team,"third","SUPERVISOR");
            var assistant=new CurrentUser(assistantId,"assistant","assistant",team,Set.of("ASSISTANT_ENGINEER"),false);
            var second=new CurrentUser(otherId,"second","second",team,Set.of("SUPERVISOR"),false);
            var third=new CurrentUser(thirdId,"third","third",team,Set.of("SUPERVISOR"),false);
            var bonus=transactionalCases.applyBonus(technician,OffsetDateTime.now(),"bonus test",List.of(),"test");
            var returned=transactionalCases.review(assistant,bonus.id(),bonus.version(),"RETURN",null,null,"needs detail","test");
            var resubmitted=transactionalCases.resubmitBonus(technician,bonus.id(),returned.version(),"new detail",List.of(),"test");
            var advanced=transactionalCases.review(assistant,bonus.id(),resubmitted.version(),"APPROVE",null,null,null,"test");
            assertThat(transactionalCases.review(supervisor,bonus.id(),advanced.version(),"APPROVE",2,"BONUS_TEST",null,"test").status()).isEqualTo("EFFECTIVE");
            var appeal=transactionalCases.appeal(technician,deduction.id(),"appeal test","test");
            assertThatThrownBy(()->transactionalCases.vote(supervisor,appeal.id(),"APPROVE",null,"test")).isInstanceOf(ApiException.class);
            assertThat(transactionalCases.vote(second,appeal.id(),"APPROVE",null,"test").status()).isEqualTo("PENDING");
            assertThatThrownBy(()->transactionalCases.vote(second,appeal.id(),"APPROVE",null,"test")).isInstanceOf(ApiException.class);
            assertThat(transactionalCases.vote(third,appeal.id(),"APPROVE",null,"test").status()).isEqualTo("ACCEPTED");
            assertThat(jdbc.sql("SELECT actual_score FROM score_event WHERE source_id=:id").param("id",appeal.id()).query(Integer.class).single()).isEqualTo(7);
            UUID outside=UUID.randomUUID();jdbc.sql("INSERT INTO org_unit(id,type,name) VALUES (:id,'TEAM','outside')").param("id",outside).update();
            UUID outsideUser=seed(jdbc,outside,"outside-tech","TECHNICIAN");
            var scopes=new com.acme.performance.organization.service.DataScopeService(jdbc);
            assertThat(scopes.canAccessUser(assistant,user)).isTrue();
            assertThat(scopes.canAccessUser(assistant,outsideUser)).isFalse();
            assertThat(scopes.canAccessUser(second,outsideUser)).isFalse();
            var outsideInput=new PerformanceCaseService.DeductionInput("SPECIAL_DEDUCTION",outsideUser,OffsetDateTime.now(),"scope diagnostic","DIAGNOSTIC",1,List.of(),null);
            assertThatThrownBy(()->transactionalCases.createDeduction(assistant,outsideInput,"test")).isInstanceOf(ApiException.class);
            // Supervisors cannot bypass organizational data scope.
            assertThatThrownBy(()->transactionalCases.createDeduction(second,outsideInput,"test")).isInstanceOf(ApiException.class);
            jdbc.sql("UPDATE score_rule SET cap_policy='{\"monthlyCap\":15}' WHERE code='DIAGNOSTIC'").update();
            var capped=transactionalCases.createDeduction(supervisor,new PerformanceCaseService.DeductionInput("SPECIAL_DEDUCTION",user,input.occurredAt(),"normalized rule cap"," DIAGNOSTIC ",20,List.of(),null),"test");
            assertThat(capped.effectiveScore()).isEqualTo(5);
            assertThat(jdbc.sql("SELECT rule_code FROM score_event WHERE source_id=:id").param("id",capped.id()).query(String.class).single()).isEqualTo("DIAGNOSTIC");
            UUID dept=UUID.randomUUID();jdbc.sql("INSERT INTO org_unit(id,type,name) VALUES (:id,'DEPARTMENT','test department')").param("id",dept).update();
            jdbc.sql("UPDATE org_unit SET parent_id=:dept WHERE id=:team").param("dept",dept).param("team",team).update();
            jdbc.sql("INSERT INTO rule_version(id,version,status,effective_at) VALUES (:id,'DIAGNOSTIC','PUBLISHED','2020-01-01')").param("id",UUID.randomUUID()).update();
            jdbc.sql("INSERT INTO score_summary(user_id,period,base,total) VALUES (:id,'2026-07',10,10)").param("id",user).update();
            jdbc.sql("INSERT INTO shift_score(id,user_id,business_date,shift_code,shift_ends_at,status) VALUES (:id,:user,'2026-07-01','DAY','2026-07-01T20:30:00+08:00','POSTED')").param("id",UUID.randomUUID()).param("user",user).update();
            var settlement=new com.acme.performance.settlement.service.SettlementService(jdbc,mock(AuditLogService.class),mock(NotificationService.class));
            assertThatThrownBy(()->settlement.preview(technician,"2026-07",dept,"diagnostic","test")).isInstanceOf(ApiException.class);
            // Team-only bindings do not authorize a department-wide settlement.
            assertThatThrownBy(()->settlement.preview(second,"2026-07",dept,"diagnostic","test")).isInstanceOf(ApiException.class);
            for(var reviewer:List.of(second,third))jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:user,'SUPERVISOR',:dept)")
                .param("id",UUID.randomUUID()).param("user",reviewer.userId()).param("dept",dept).update();
            var run=settlement.preview(second,"2026-07",dept,"diagnostic","test");
            for(var candidate:run.candidates())if(candidate.manualRequired()){
                settlement.voteBoundary(second,run.runId(),candidate.userId(),candidate.proposedGrade(),"test","test");
                settlement.voteBoundary(third,run.runId(),candidate.userId(),candidate.proposedGrade(),"test","test");
            }
            assertThat(settlement.confirm(second,run.runId(),null,"test").status()).isEqualTo("PREVIEW");
            assertThat(settlement.confirm(third,run.runId(),null,"test").status()).isEqualTo("PUBLISHED");
            assertThatThrownBy(()->settlement.confirm(second,run.runId(),null,"test")).isInstanceOf(ApiException.class);
            var idem=new IdempotencyService(jdbc);
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)){
                var jobs=new java.util.ArrayList<java.util.concurrent.Callable<IdempotencyService.StartState>>();
                for(int n=0;n<8;n++)jobs.add(()->idem.start(user,"concurrent","POST","/api/test").state());
                int started=0;for(var future:pool.invokeAll(jobs))if(future.get()==IdempotencyService.StartState.STARTED)started++;
                assertThat(started).isEqualTo(1);
            }
            var s3=mock(software.amazon.awssdk.services.s3.S3Client.class);
            byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
            when(s3.getObjectAsBytes(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenReturn(software.amazon.awssdk.core.ResponseBytes.fromByteArray(software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),png));
            var attachments=new com.acme.performance.attachment.service.AttachmentService(s3,jdbc,scopes,"isolated");
            assertThatThrownBy(()->attachments.upload(user,new org.springframework.mock.web.MockMultipartFile("file","fake.png","image/png",new byte[]{1,2,3}))).isInstanceOf(ApiException.class);
            var uploaded=attachments.upload(user,new org.springframework.mock.web.MockMultipartFile("file","pixel.png","image/png",png));
            assertThat(attachments.download(technician,uploaded.id()).content()).isEqualTo(png);
            var outsider=new CurrentUser(outsideUser,"outside-tech","outside",outside,Set.of("TECHNICIAN"),false);
            assertThatThrownBy(()->attachments.download(outsider,uploaded.id())).isInstanceOf(ApiException.class);
        }
    }
    private UUID seed(JdbcClient jdbc,UUID team,String name,String role){
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status) VALUES (:id,:name,:name,:team,'ACTIVE')").param("id",id).param("name",name).param("team",team).update();
        jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:user,:role,:team)").param("id",UUID.randomUUID()).param("user",id).param("role",role).param("team",team).update();return id;
    }
}
