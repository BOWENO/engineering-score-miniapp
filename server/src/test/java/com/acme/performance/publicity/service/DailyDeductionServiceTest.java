package com.acme.performance.publicity.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DailyDeductionServiceTest {
    EmbeddedPostgres pg;JdbcClient jdbc;DailyDeductionService service;
    UUID department,other,team,reviewOrg;CurrentUser tech,assistant,foreign,review,manager;
    final LocalDate day=LocalDate.of(2025,1,2);
    @BeforeAll void start() throws Exception {
        pg=EmbeddedPostgres.builder().start();var ds=pg.getPostgresDatabase();Flyway.configure().dataSource(ds).load().migrate();
        jdbc=JdbcClient.create(ds);service=new DailyDeductionService(jdbc);
    }
    @AfterAll void stop() throws Exception {if(pg!=null)pg.close();}
    @BeforeEach void fixture(){department=org(null,"DEPARTMENT",false);other=org(null,"DEPARTMENT",false);team=org(department,"TEAM",false);reviewOrg=org(department,"TEAM",true);
        tech=user(team,"TECHNICIAN",false);assistant=user(department,"ASSISTANT_ENGINEER",false);foreign=user(other,"TECHNICIAN",false);review=user(department,"TECHNICIAN",true);manager=user(department,"SUPERVISOR",false);}
    UUID org(UUID parent,String type,boolean review){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO org_unit(id,parent_id,type,name,is_review_data) VALUES (:id,:parent,:type,:name,:review)").param("id",id).param("parent",parent).param("type",type).param("name",id.toString()).param("review",review).update();return id;}
    CurrentUser user(UUID org,String role,boolean review){UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status,is_review_account) VALUES (:id,:name,:name,:org,'ACTIVE',:review)").param("id",id).param("name",id.toString()).param("org",org).param("review",review).update();jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:user,:role,:org)").param("id",UUID.randomUUID()).param("user",id).param("role",role).param("org",org).update();return new CurrentUser(id,id.toString(),id.toString(),org,Set.of(role),false);}
    UUID entry(CurrentUser user,int score,String status,String type,String effective){UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO performance_case(id,case_type,target_user_id,initiated_by,occurred_at,description,effective_score,status) VALUES (:id,:type,:user,:user,'2024-12-31T12:00:00+08:00','公开事项，不包含附件链接',:score,:status)")
            .param("id",id).param("type",type).param("user",user.userId()).param("score",Math.abs(score)).param("status",status).update();
        jdbc.sql("INSERT INTO score_event(id,user_id,rule_code,biz_date,original_score,actual_score,source,source_id,created_at) VALUES (:id,:user,'TEST','2024-12-31',:score,:score,'PERFORMANCE_CASE',:case,:effective)")
            .param("id",UUID.randomUUID()).param("user",user.userId()).param("score",score).param("case",id).param("effective",OffsetDateTime.parse(effective)).update();return id;}

    @Test void publicizesBothScoredRolesToColleaguesWithinTheDepartment(){
        entry(tech,-3,"EFFECTIVE","BASE_DEDUCTION","2025-01-02T08:00:00+08:00");entry(assistant,-5,"EFFECTIVE","SPECIAL_DEDUCTION","2025-01-02T09:00:00+08:00");
        entry(foreign,-9,"EFFECTIVE","BASE_DEDUCTION","2025-01-02T08:00:00+08:00");entry(review,-7,"EFFECTIVE","BASE_DEDUCTION","2025-01-02T08:00:00+08:00");entry(manager,-6,"EFFECTIVE","BASE_DEDUCTION","2025-01-02T08:00:00+08:00");entry(user(reviewOrg,"TECHNICIAN",false),-10,"EFFECTIVE","BASE_DEDUCTION","2025-01-02T08:00:00+08:00");
        var board=service.board(tech,day,0,10);assertThat(board.total()).isEqualTo(2);assertThat(board.people()).isEqualTo(2);assertThat(board.totalScore()).isEqualByComparingTo("8");assertThat(board.items()).extracting(DailyDeductionService.Item::roleName).containsExactly("助理工程师","技术员");
        assertThat(service.board(manager,day,0,10).total()).isEqualTo(2);assertThat(service.board(foreign,day,0,10).total()).isEqualTo(1);assertThat(service.board(review,day,0,10).total()).isZero();
    }
    @Test void usesShanghaiEffectiveDayAndExcludesPendingReversedBonusAndZero(){
        entry(tech,-2,"EFFECTIVE","BASE_DEDUCTION","2025-01-01T15:59:59Z"); // previous Shanghai day
        entry(tech,-3,"EFFECTIVE","BASE_DEDUCTION","2025-01-01T16:00:00Z"); // 00:00 inclusive
        entry(tech,-4,"EFFECTIVE","SPECIAL_DEDUCTION","2025-01-02T15:59:59Z");
        entry(tech,-5,"EFFECTIVE","BASE_DEDUCTION","2025-01-02T16:00:00Z"); // next day excluded
        entry(tech,-6,"PENDING_SUPERVISOR","BASE_DEDUCTION","2025-01-02T09:00:00Z");
        entry(tech,-7,"REVERSED","BASE_DEDUCTION","2025-01-02T09:00:00Z");entry(tech,2,"EFFECTIVE","BONUS","2025-01-02T09:00:00Z");entry(tech,0,"EFFECTIVE","BASE_DEDUCTION","2025-01-02T09:00:00Z");
        var board=service.board(tech,day,0,10);assertThat(board.total()).isEqualTo(2);assertThat(board.totalScore()).isEqualByComparingTo("7");assertThat(board.items()).extracting(DailyDeductionService.Item::effectiveTime).containsExactly("23:59","00:00");assertThat(board.items()).allMatch(item->item.occurredTime().equals("12-31 12:00"));
    }
    @Test void paginationKeepsFullTotalsAndFlagsAppealsWithoutDuplicatingDualRoles(){
        jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:user,'ASSISTANT_ENGINEER',:scope)").param("id",UUID.randomUUID()).param("user",tech.userId()).param("scope",team).update();
        UUID appealed=null;for(int i=0;i<5;i++)appealed=entry(tech,-1,"EFFECTIVE","BASE_DEDUCTION","2025-01-02T09:00:00+08:00");
        jdbc.sql("INSERT INTO performance_appeal(id,case_id,applicant_id,description,status,due_at) VALUES (:id,:case,:user,'appeal','PENDING',CURRENT_TIMESTAMP)").param("id",UUID.randomUUID()).param("case",appealed).param("user",tech.userId()).update();
        var first=service.board(tech,day,0,3);var next=service.board(tech,day,1,3);
        assertThat(first.items()).hasSize(3);assertThat(next.items()).hasSize(2);assertThat(first.hasMore()).isTrue();assertThat(next.hasMore()).isFalse();assertThat(first.total()).isEqualTo(5);assertThat(next.totalScore()).isEqualByComparingTo("5");assertThat(first.people()).isEqualTo(1);
        var all=new ArrayList<>(first.items());all.addAll(next.items());assertThat(all.stream().map(DailyDeductionService.Item::id).distinct().count()).isEqualTo(5);assertThat(all.stream().filter(DailyDeductionService.Item::underAppeal).count()).isEqualTo(1);
    }
    @Test void rejectsInvalidQueriesAndUnavailableAccounts(){
        assertThatThrownBy(()->service.board(tech,day,-1,10)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.board(tech,day,0,51)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.board(tech,LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1),0,10)).isInstanceOf(ApiException.class);
        assertThat(service.board(tech,day,0,10).items()).isEmpty();
        jdbc.sql("UPDATE app_user SET status='DISABLED' WHERE id=:id").param("id",tech.userId()).update();assertThatThrownBy(()->service.board(tech,day,0,10)).isInstanceOf(ApiException.class);
    }
}
