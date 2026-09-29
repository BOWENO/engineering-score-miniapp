package com.acme.performance.settlement.service;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.notification.service.NotificationService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL and transactions; every fixture is isolated from production. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettlementIsolationTest {
    EmbeddedPostgres pg; JdbcClient jdbc; TransactionTemplate tx;
    SettlementService settlements; SettlementCorrectionService corrections;
    UUID orgA,orgB,rule,upgradedRun; CurrentUser a,b,tech,upgradeSupervisor;
    final String period="2025-01";

    @BeforeAll void start() throws Exception {
        pg=EmbeddedPostgres.builder().start();var ds=pg.getPostgresDatabase();
        Flyway.configure().dataSource(ds).target("22").load().migrate();jdbc=JdbcClient.create(ds);
        UUID upgradeOrg=org(false),foreignOrg=org(false),upgradeRule=UUID.randomUUID();
        upgradeSupervisor=user(upgradeOrg,"SUPERVISOR",false);
        user(upgradeOrg,"SUPERVISOR",true);user(foreignOrg,"SUPERVISOR",false);
        jdbc.sql("INSERT INTO rule_version(id,version,status,effective_at) VALUES (:id,'UPGRADE','PUBLISHED','2020-01-01')").param("id",upgradeRule).update();
        upgradedRun=UUID.randomUUID();
        jdbc.sql("INSERT INTO settlement_run(id,period,org_unit_id,settlement_version,rule_version_id,status,created_by) VALUES (:id,'2024-01',:org,'UPGRADE',:rule,'PREVIEW',:actor)")
            .param("id",upgradedRun).param("org",upgradeOrg).param("rule",upgradeRule).param("actor",upgradeSupervisor.userId()).update();
        Flyway.configure().dataSource(ds).load().migrate();
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var audit=mock(AuditLogService.class);var notifications=mock(NotificationService.class);
        settlements=new SettlementService(jdbc,audit,notifications);
        corrections=new SettlementCorrectionService(jdbc,settlements,notifications,audit);
    }
    @AfterAll void stop() throws Exception {if(pg!=null)pg.close();}
    @BeforeEach void fixture(){
        orgA=org(false);orgB=org(false);a=user(orgA,"SUPERVISOR",false);b=user(orgB,"SUPERVISOR",false);tech=user(orgA,"TECHNICIAN",false);
        rule=UUID.randomUUID();jdbc.sql("INSERT INTO rule_version(id,version,status,effective_at) VALUES (:id,:version,'PUBLISHED','2020-01-01')")
            .param("id",rule).param("version",rule.toString().replace("-", "")).update();
        jdbc.sql("INSERT INTO shift_score(id,user_id,business_date,shift_code,shift_ends_at,status) VALUES (:id,:user,'2025-01-01','DAY','2025-01-01T20:00:00+08:00','POSTED')")
            .param("id",UUID.randomUUID()).param("user",tech.userId()).update();
    }
    UUID org(boolean review){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO org_unit(id,type,name,is_review_data) VALUES (:id,'DEPARTMENT',:name,:review)").param("id",id).param("name",id.toString()).param("review",review).update();return id;}
    CurrentUser user(UUID org,String role,boolean review){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status,is_review_account) VALUES (:id,:name,:name,:org,'ACTIVE',:review)").param("id",id).param("name",id.toString()).param("org",org).param("review",review).update();jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:user,:role,:org)").param("id",UUID.randomUUID()).param("user",id).param("role",role).param("org",org).update();return new CurrentUser(id,id.toString(),id.toString(),org,Set.of(role),false);}
    <T>T transaction(Supplier<T> action){return tx.execute(status->action.get());}
    UUID preview(){return transaction(()->settlements.preview(a,period,orgA,"TEST-"+UUID.randomUUID().toString().substring(0,8),"test")).runId();}
    UUID published(){UUID id=preview();transaction(()->settlements.confirm(a,id,"ok","test"));return id;}
    void forbidden(Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(403));}

    @Test void migrationBackfillsOnlyAuthorizedFormalSupervisors(){
        assertThat(jdbc.sql("SELECT user_id FROM settlement_approver WHERE run_id=:id").param("id",upgradedRun).query(UUID.class).list())
            .containsExactly(upgradeSupervisor.userId());
    }

    @Test void rejectsCrossOrganizationReadsWritesAndReviewAccounts(){
        UUID run=published();
        forbidden(()->settlements.view(b,run));assertThat(settlements.list(b,period)).noneMatch(r->r.runId().equals(run));
        forbidden(()->transaction(()->settlements.preview(b,period,orgA,"FORBIDDEN","test")));
        forbidden(()->transaction(()->settlements.publish(b,run,"test")));
        forbidden(()->transaction(()->corrections.request(b,run,tech.userId(),2,"reason","test")));
        var correction=transaction(()->corrections.request(a,run,tech.userId(),2,"reason","test"));
        assertThat(corrections.list(b,period)).noneMatch(c->c.id().equals(correction.id()));
        forbidden(()->transaction(()->corrections.vote(b,correction.id(),"REJECT","no","test")));
        var review=user(orgA,"SUPERVISOR",true);forbidden(()->settlements.view(review,run));
        assertThat(corrections.list(review,period)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM settlement_correction_vote WHERE correction_id=:id").param("id",correction.id()).query(Long.class).single()).isZero();
    }

    @Test void scopeOfAnUnrelatedRoleDoesNotGrantSupervisorAuthority(){
        jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:user,'ASSISTANT_ENGINEER',:org)").param("id",UUID.randomUUID()).param("user",b.userId()).param("org",orgA).update();
        var dual=new CurrentUser(b.userId(),b.employeeNo(),b.displayName(),orgB,Set.of("SUPERVISOR","ASSISTANT_ENGINEER"),false);
        forbidden(()->transaction(()->settlements.preview(dual,period,orgA,"NO","test")));
    }

    @Test void electorateIsFrozenAndDoesNotIncludeOtherDepartments(){
        UUID run=preview();assertThat(settlements.view(a,run).requiredConfirmations()).isEqualTo(1);
        var late=user(orgA,"SUPERVISOR",false);
        assertThat(settlements.view(a,run).requiredConfirmations()).isEqualTo(1);
        forbidden(()->transaction(()->settlements.confirm(late,run,"late","test")));
        assertThat(transaction(()->settlements.confirm(a,run,"ok","test")).status()).isEqualTo("PUBLISHED");
        var correction=transaction(()->corrections.request(a,run,tech.userId(),2,"reason","test"));
        user(orgA,"SUPERVISOR",false);
        assertThat(transaction(()->corrections.vote(a,correction.id(),"APPROVE","yes","test")).status()).isEqualTo("PENDING");
        assertThat(transaction(()->corrections.vote(late,correction.id(),"APPROVE","yes","test")).status()).isEqualTo("ACCEPTED");
    }

    @Test void publicationIsSerializedWithOneSnapshot() throws Exception {
        UUID run=preview();
        // Valid confirmation exists; two independent requests both attempt publication.
        jdbc.sql("INSERT INTO settlement_confirmation(id,run_id,supervisor_id) VALUES (:id,:run,:user)").param("id",UUID.randomUUID()).param("run",run).param("user",a.userId()).update();
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++)tasks.add(pool.submit(()->{gate.await();try{transaction(()->settlements.publish(a,run,"parallel"));return 200;}catch(ApiException ex){return ex.status().value();}}));
            gate.countDown();assertThat(List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM grade_snapshot WHERE settlement_run_id=:id").param("id",run).query(Long.class).single()).isEqualTo(1);
    }

    @Test void onlyOneConcurrentCorrectionChangesTheOriginalBatch() throws Exception {
        UUID run=published();
        var first=transaction(()->corrections.request(a,run,tech.userId(),2,"reason","test"));
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++)tasks.add(pool.submit(()->{gate.await();try{transaction(()->corrections.vote(a,first.id(),"APPROVE","yes","parallel"));return 200;}catch(ApiException ex){return ex.status().value();}}));
            gate.countDown();assertThat(List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM score_event WHERE source_id=:id").param("id",first.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT total FROM score_summary WHERE user_id=:id AND period=:period").param("id",tech.userId()).param("period",period).query(Integer.class).single()).isEqualTo(2);
    }

    @Test void pendingBusinessExcludesOtherOrganizationsAndReviewDomains(){
        pendingFixtures(b);pendingFixtures(user(orgA,"TECHNICIAN",true));pendingFixtures(user(org(true),"TECHNICIAN",false));
        assertThat(settlements.pendingCount(YearMonth.parse(period),orgA)).isZero();
        pendingFixtures(tech);
        assertThat(settlements.pendingCount(YearMonth.parse(period),orgA)).isEqualTo(5);
        assertThatThrownBy(()->transaction(()->settlements.preview(a,period,orgA,"BLOCK","test"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PENDING_BUSINESS_EXISTS"));
    }

    @Test void competingCorrectionsAreMarkedForResubmissionInsteadOfRemainingPending() throws Exception {
        CurrentUser second=user(orgA,"TECHNICIAN",false);
        jdbc.sql("INSERT INTO shift_score(id,user_id,business_date,shift_code,shift_ends_at,status) VALUES (:id,:user,'2025-01-01','DAY','2025-01-01T20:00:00+08:00','POSTED')").param("id",UUID.randomUUID()).param("user",second.userId()).update();
        jdbc.sql("INSERT INTO score_summary(user_id,period,total) VALUES (:user,:period,10)").param("user",second.userId()).param("period",period).update();
        UUID run=published();
        var c1=transaction(()->corrections.request(a,run,tech.userId(),2,"first","test"));
        var c2=transaction(()->corrections.request(a,run,second.userId(),2,"second","test"));
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(UUID id:List.of(c1.id(),c2.id()))tasks.add(pool.submit(()->{gate.await();try{transaction(()->corrections.vote(a,id,"APPROVE","yes","parallel"));return 200;}catch(ApiException ex){return ex.status().value();}}));
            gate.countDown();assertThat(List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.sql("SELECT status FROM settlement_correction WHERE settlement_run_id=:id").param("id",run).query(String.class).list()).containsExactlyInAnyOrder("ACCEPTED","SUPERSEDED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM score_event WHERE source_id IN (:ids)").param("ids",List.of(c1.id(),c2.id())).query(Long.class).single()).isEqualTo(1);
    }

    void pendingFixtures(CurrentUser actor){
        UUID c=UUID.randomUUID(),d=UUID.randomUUID(),line=UUID.randomUUID(),station=UUID.randomUUID();
        jdbc.sql("INSERT INTO performance_case(id,case_type,target_user_id,initiated_by,occurred_at,description,status) VALUES (:id,'BONUS',:user,:user,'2025-01-10T10:00:00+08:00','pending','PENDING_ASSISTANT')").param("id",c).param("user",actor.userId()).update();
        jdbc.sql("INSERT INTO performance_appeal(id,case_id,applicant_id,description,status,due_at) VALUES (:id,:case,:user,'appeal','PENDING',CURRENT_TIMESTAMP)").param("id",UUID.randomUUID()).param("case",c).param("user",actor.userId()).update();
        jdbc.sql("INSERT INTO d_grade_nomination(id,period,user_id,org_unit_id,reason_code,description,status,nominated_by) VALUES (:id,:period,:user,:org,'TEST','pending','PENDING',:user)").param("id",d).param("period",period).param("user",actor.userId()).param("org",actor.orgUnitId()).update();
        jdbc.sql("INSERT INTO d_grade_appeal(id,nomination_id,applicant_id,description,status,due_at) VALUES (:id,:nomination,:user,'appeal','PENDING',CURRENT_TIMESTAMP)").param("id",UUID.randomUUID()).param("nomination",d).param("user",actor.userId()).update();
        jdbc.sql("INSERT INTO production_line(id,org_unit_id,code,name,status) VALUES (:id,:org,:name,:name,'ACTIVE')").param("id",line).param("org",actor.orgUnitId()).param("name",line.toString()).update();
        jdbc.sql("INSERT INTO station(id,line_id,code,name,status) VALUES (:id,:line,'test','test','ACTIVE')").param("id",station).param("line",line).update();
        jdbc.sql("INSERT INTO equipment_incident(id,incident_no,occurred_at,business_date,shift_code,team_id,line_id,station_id,status,created_by) VALUES (:id,:no,'2025-01-10T10:00:00+08:00','2025-01-10','DAY',:org,:line,:station,'WAITING_STATEMENTS',:user)").param("id",UUID.randomUUID()).param("no",UUID.randomUUID().toString().substring(0,30)).param("org",actor.orgUnitId()).param("line",line).param("station",station).param("user",actor.userId()).update();
    }
}
