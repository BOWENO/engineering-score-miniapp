package com.acme.performance.migration;

import com.acme.performance.EngineeringScoreApplication;
import com.acme.performance.auth.service.AdminAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Actual HTTP, authentication, Spring transactions and isolated PostgreSQL. No production credentials. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullStackAcceptanceTest {
    EmbeddedPostgres postgres;
    ServletWebServerApplicationContext context;
    JdbcClient jdbc;
    ObjectMapper mapper = new ObjectMapper();
    HttpClient client = HttpClient.newHttpClient();
    String base;
    final String password = "Acceptance2026!";
    final Map<String,String> tokens = new LinkedHashMap<>();
    final List<String> common = List.of("/me", "/workbench", "/catalog", "/directory/people",
            "/notifications", "/notifications/subscription-config", "/performance-cases/mine",
            "/incidents/mine/pending", "/d-grades/mine", "/publicity/daily-deductions");
    final List<String> management = List.of("/schedules?from=2026-09-01&to=2026-09-30",
            "/incidents/archive", "/incidents/open", "/incidents/dossiers/open", "/incident-reports/monthly",
            "/performance-cases/records?period=2026-09");

    @BeforeAll void start() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        context = (ServletWebServerApplicationContext) SpringApplication.run(EngineeringScoreApplication.class,
            "--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url="+postgres.getJdbcUrl("postgres","postgres"),
            "--spring.datasource.username=postgres", "--spring.datasource.password=",
            "--spring.datasource.driver-class-name=org.postgresql.Driver", "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=none", "--wechat.app-id=isolated-test",
            "--wechat.app-secret=isolated-test", "--wechat.subscribe-template-id=",
            "--object-storage.endpoint=http://127.0.0.1:1",
            "--object-storage.access-key=isolated-test", "--object-storage.secret-key=isolated-test",
            "--object-storage.bucket=isolated-test");
        base = "http://127.0.0.1:"+context.getWebServer().getPort()+"/api";
        jdbc = context.getBean(JdbcClient.class);
        UUID department=UUID.randomUUID();
        jdbc.sql("INSERT INTO org_unit(id,type,name) VALUES (:id,'DEPARTMENT','验收测试部门')").param("id",department).update();
        for(String role: List.of("TECHNICIAN","ASSISTANT_ENGINEER","SUPERVISOR","DEPARTMENT_MANAGER","ADMINISTRATOR")) {
            seed(role,department,role);
            var response=login(role,password,"MINIPROGRAM");
            assertThat(response.statusCode()).as("login %s",role).isEqualTo(200);
            tokens.put(role,mapper.readTree(response.body()).path("data").path("accessToken").asText());
        }
        seed("LOCK_TEST",department,"TECHNICIAN");
    }
    void seed(String name,UUID org,String role) {
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status,is_administrator,password_change_required) VALUES (:id,:name,:name,:org,'ACTIVE',:admin,FALSE)")
            .param("id",id).param("name",name).param("org",org).param("admin",role.equals("ADMINISTRATOR")).update();
        if(!role.equals("ADMINISTRATOR")) jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:user,:role,:org)")
            .param("id",UUID.randomUUID()).param("user",id).param("role",role).param("org",org).update();
        jdbc.sql("INSERT INTO admin_credential(user_id,username,password_hash) VALUES (:id,:name,:hash)")
            .param("id",id).param("name",name).param("hash",context.getBean(AdminAuthService.class).encodePassword(password)).update();
    }
    HttpResponse<String> request(String method,String path,String token,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(20));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(method.equals("GET"))builder.GET();
        else builder.header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
            .method(method,HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> login(String name,String supplied,String type) throws Exception {
        return request("POST","/auth/account/login",null,Map.of("username",name,"password",supplied,"clientType",type));
    }
    @Test void notificationPagingAndOwnership() throws Exception {
        UUID owner=jdbc.sql("SELECT id FROM app_user WHERE employee_no='TECHNICIAN'").query(UUID.class).single();
        var service=context.getBean(com.acme.performance.notification.service.NotificationService.class);
        UUID first=null;
        for(int i=0;i<205;i++){UUID id=service.create(owner,"UX_TEST","消息分页测试","隔离样例",null,null,false);if(first==null)first=id;}
        var one=mapper.readTree(request("GET","/notifications?limit=50&offset=0&unreadOnly=true",tokens.get("TECHNICIAN"),null).body()).path("data");
        assertThat(one.path("items").size()).isEqualTo(50);assertThat(one.path("unread").asLong()).isGreaterThanOrEqualTo(205);assertThat(one.path("hasMore").asBoolean()).isTrue();
        var two=mapper.readTree(request("GET","/notifications?limit=50&offset=50&unreadOnly=true",tokens.get("TECHNICIAN"),null).body()).path("data");
        Set<String> ids=new HashSet<>();one.path("items").forEach(i->ids.add(i.path("id").asText()));two.path("items").forEach(i->assertThat(ids).doesNotContain(i.path("id").asText()));
        var other=mapper.readTree(request("GET","/notifications/"+first+"/source-date",tokens.get("SUPERVISOR"),null).body()).path("data");assertThat(other.asText()).isEmpty();
        assertThat(request("GET","/notifications/"+first+"/source-date",null,null).statusCode()).isEqualTo(401);
    }
    @Test void rolePageQueriesAndAnonymousGuards() throws Exception {
        var failures=new ArrayList<String>(); int count=0;
        for(var actor:tokens.entrySet()) {
            var paths=new ArrayList<>(common);
            if(List.of("ASSISTANT_ENGINEER","SUPERVISOR","DEPARTMENT_MANAGER").contains(actor.getKey())) paths.addAll(management);
            if(List.of("TECHNICIAN","ASSISTANT_ENGINEER").contains(actor.getKey()))paths.add("/performance/me?period=2026-09");
            if(List.of("SUPERVISOR","DEPARTMENT_MANAGER").contains(actor.getKey()))paths.addAll(List.of("/performance/overview?period=2026-09","/settlements?period=2026-09","/settlement-corrections?period=2026-09","/d-grades?period=2026-09"));
            if(List.of("DEPARTMENT_MANAGER","ADMINISTRATOR").contains(actor.getKey()))paths.addAll(List.of("/admin/organizations","/admin/rule-versions"));
            if(actor.getKey().equals("ADMINISTRATOR"))paths.addAll(List.of("/admin/users","/admin/equipment","/admin/catalog","/admin/audit-logs?limit=200"));
            for(String path:paths) {
                var result=request("GET",path,actor.getValue(),null);count++;
                System.out.printf("HTTP_MATRIX %s %s %d%n",actor.getKey(),path,result.statusCode());
                if(result.statusCode()!=200)failures.add(actor.getKey()+" "+path+" => "+result.statusCode()+" "+result.body());
                assertThat(request("GET",path,null,null).statusCode()).as("anonymous %s",path).isEqualTo(401);count++;
            }
        }
        System.out.println("HTTP_MATRIX requests="+count);
        assertThat(failures).isEmpty();
    }
    @Test void webLoginRoleRestrictionsAndLogout() throws Exception {
        for(String role:tokens.keySet())assertThat(login(role,password,"WEB").statusCode()).as(role).isEqualTo(role.equals("TECHNICIAN")?403:200);
        var response=login("SUPERVISOR",password,"WEB");
        String token=mapper.readTree(response.body()).path("data").path("accessToken").asText();
        assertThat(request("POST","/auth/logout",token,Map.of()).statusCode()).isEqualTo(200);
        assertThat(request("GET","/me",token,null).statusCode()).isEqualTo(401);
    }
    @Test void wrongPasswordsPersistAndLockAfterFiveAttempts() throws Exception {
        var statuses=new ArrayList<Integer>();
        for(int i=0;i<5;i++)statuses.add(login("LOCK_TEST","wrong-password","MINIPROGRAM").statusCode());
        int stored=jdbc.sql("SELECT failed_login_count FROM app_user WHERE employee_no='LOCK_TEST'").query(Integer.class).single();
        System.out.println("LOGIN_LOCK statuses="+statuses+" storedFailures="+stored);
        assertThat(statuses).containsExactly(401,401,401,401,423);
        assertThat(stored).isEqualTo(5);
        assertThat(login("LOCK_TEST",password,"MINIPROGRAM").statusCode()).isEqualTo(423);
        assertThat(jdbc.sql("SELECT locked_until > CURRENT_TIMESTAMP + INTERVAL '29 minutes' AND locked_until <= CURRENT_TIMESTAMP + INTERVAL '30 minutes' FROM app_user WHERE employee_no='LOCK_TEST'").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM access_token t JOIN app_user u ON t.user_id=u.id WHERE u.employee_no='LOCK_TEST'").query(Integer.class).single()).isZero();
    }
    UUID seedLoginAccount(String name) {
        UUID org=jdbc.sql("SELECT org_unit_id FROM app_user WHERE employee_no='TECHNICIAN'").query(UUID.class).single();
        seed(name,org,"TECHNICIAN");
        return jdbc.sql("SELECT id FROM app_user WHERE employee_no=:name").param("name",name).query(UUID.class).single();
    }
    int failuresFor(UUID id) {
        return jdbc.sql("SELECT failed_login_count FROM app_user WHERE id=:id").param("id",id).query(Integer.class).single();
    }
    @Test void successfulLoginClearsFailuresAndExpiredLockStartsFresh() throws Exception {
        UUID id=seedLoginAccount("LOCK_RESET");
        for(int i=1;i<=2;i++) {
            assertThat(login("LOCK_RESET","wrong","MINIPROGRAM").statusCode()).isEqualTo(401);
            assertThat(failuresFor(id)).isEqualTo(i);
        }
        assertThat(login("LOCK_RESET",password,"MINIPROGRAM").statusCode()).isEqualTo(200);
        assertThat(failuresFor(id)).isZero();
        jdbc.sql("UPDATE app_user SET failed_login_count=5,locked_until=CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id=:id").param("id",id).update();
        assertThat(login("LOCK_RESET","wrong","MINIPROGRAM").statusCode()).isEqualTo(401);
        assertThat(failuresFor(id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT locked_until IS NULL FROM app_user WHERE id=:id").param("id",id).query(Boolean.class).single()).isTrue();
        assertThat(login("LOCK_RESET",password,"MINIPROGRAM").statusCode()).isEqualTo(200);
        assertThat(failuresFor(id)).isZero();
    }
    @Test void administratorCanUnlockButSupervisorCannot() throws Exception {
        UUID id=seedLoginAccount("LOCK_ADMIN");
        jdbc.sql("UPDATE app_user SET failed_login_count=5,locked_until=CURRENT_TIMESTAMP + INTERVAL '30 minutes' WHERE id=:id").param("id",id).update();
        assertThat(request("POST","/admin/users/"+id+"/unlock",tokens.get("SUPERVISOR"),Map.of()).statusCode()).isEqualTo(403);
        assertThat(login("LOCK_ADMIN",password,"MINIPROGRAM").statusCode()).isEqualTo(423);
        assertThat(request("POST","/admin/users/"+id+"/unlock",tokens.get("ADMINISTRATOR"),Map.of()).statusCode()).isEqualTo(200);
        assertThat(failuresFor(id)).isZero();
        assertThat(login("LOCK_ADMIN",password,"MINIPROGRAM").statusCode()).isEqualTo(200);
    }
    @Test void simultaneousWrongPasswordsDoNotLoseAttempts() throws Exception {
        UUID id=seedLoginAccount("LOCK_CONCURRENT");
        var start=new java.util.concurrent.CountDownLatch(1);
        var futures=new ArrayList<java.util.concurrent.Future<Integer>>();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<12;i++)futures.add(executor.submit(()->{start.await();return login("LOCK_CONCURRENT","wrong","MINIPROGRAM").statusCode();}));
            start.countDown();
            var statuses=new ArrayList<Integer>();
            for(var future:futures)statuses.add(future.get(30,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(401,423);
            assertThat(Collections.frequency(statuses,401)).isEqualTo(4);
            assertThat(Collections.frequency(statuses,423)).isEqualTo(8);
        }
        assertThat(failuresFor(id)).isEqualTo(5);
    }
    @Test void wechatBindingRollbackPreservesWrongPasswordCount() throws Exception {
        UUID id=seedLoginAccount("LOCK_BINDING");
        String ticket=UUID.randomUUID().toString();
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.sql("INSERT INTO wechat_binding_ticket(id,token_hash,openid,expires_at) VALUES (:id,:hash,'isolated-binding-openid',CURRENT_TIMESTAMP + INTERVAL '10 minutes')")
            .param("id",UUID.randomUUID()).param("hash",hash).update();
        for(int i=1;i<=5;i++) {
            var result=request("POST","/auth/wechat/bind-account",null,Map.of("bindingToken",ticket,"username","LOCK_BINDING","password","wrong"));
            assertThat(result.statusCode()).isEqualTo(i==5?423:401);
            assertThat(failuresFor(id)).isEqualTo(i);
        }
        assertThat(jdbc.sql("SELECT consumed_at IS NULL FROM wechat_binding_ticket WHERE token_hash=:hash").param("hash",hash).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT openid IS NULL FROM app_user WHERE id=:id").param("id",id).query(Boolean.class).single()).isTrue();
    }
    @Test void failedWechatBindingDoesNotLeaveAnIssuedToken() throws Exception {
        UUID id=seedLoginAccount("BIND_CONFLICT");
        jdbc.sql("UPDATE app_user SET openid='already-bound-test' WHERE id=:id").param("id",id).update();
        String ticket=UUID.randomUUID().toString();
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.sql("INSERT INTO wechat_binding_ticket(id,token_hash,openid,expires_at) VALUES (:id,:hash,'different-test-openid',CURRENT_TIMESTAMP + INTERVAL '10 minutes')")
            .param("id",UUID.randomUUID()).param("hash",hash).update();
        var result=request("POST","/auth/wechat/bind-account",null,Map.of("bindingToken",ticket,"username","BIND_CONFLICT","password",password));
        assertThat(result.statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM access_token WHERE user_id=:id").param("id",id).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT last_login_at IS NULL FROM app_user WHERE id=:id").param("id",id).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT consumed_at IS NULL FROM wechat_binding_ticket WHERE token_hash=:hash").param("hash",hash).query(Boolean.class).single()).isTrue();
    }
    @Test void administrativeReadsDenyBusinessRoles() throws Exception {
        for(String role:List.of("TECHNICIAN","ASSISTANT_ENGINEER","SUPERVISOR"))
            for(String path:List.of("/admin/users","/admin/audit-logs","/admin/equipment"))
                assertThat(request("GET",path,tokens.get(role),null).statusCode()).as(role+" "+path).isEqualTo(403);
    }
    @Test void spreadsheetExportsProduceReadableWorkbooks() throws Exception {
        for(String path:List.of("/exports/performance?period=2026-09","/exports/schedules?from=2026-09-01&to=2026-09-30","/exports/incidents","/exports/incidents/monthly","/exports/equipment")) {
            String role=path.endsWith("equipment")?"ADMINISTRATOR":"SUPERVISOR";
            var response=client.send(HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+tokens.get(role)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.body()))) {
                assertThat(workbook.getNumberOfSheets()).as(path).isPositive();
                assertThat((Object)workbook.getSheetAt(0).getRow(0)).as(path+" header").isNotNull();
            }
            System.out.println("EXPORT_OK "+path);
        }
    }
    @Test void longPollWakesAfterCommittedChangeOverAuthenticatedHttp() throws Exception {
        String token=tokens.get("DEPARTMENT_MANAGER");
        assertThat(request("GET","/score-changes",null,null).statusCode()).isEqualTo(401);
        String revision=mapper.readTree(request("GET","/score-changes",token,null).body()).path("data").path("revision").asText();
        assertThat(revision).isNotBlank();
        var waiting=client.sendAsync(HttpRequest.newBuilder(URI.create(base+"/score-changes?since="+revision))
            .header("Authorization","Bearer "+token).timeout(java.time.Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
        jdbc.sql("UPDATE score_summary SET total=total WHERE false").update();
        var response=waiting.get(10,java.util.concurrent.TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("data").path("revision").asText()).isNotEqualTo(revision);
    }
    @Test void invalidMonthReturnsClientError() throws Exception {
        var result=request("GET","/performance/overview?period=not-a-month",tokens.get("SUPERVISOR"),null);
        System.out.println("INVALID_MONTH status="+result.statusCode()+" body="+result.body());
        assertThat(result.statusCode()).isEqualTo(400);
        assertThat(request("GET","/publicity/daily-deductions?date=invalid",tokens.get("TECHNICIAN"),null).statusCode()).isEqualTo(400);
        assertThat(request("GET","/publicity/daily-deductions?size=999",tokens.get("TECHNICIAN"),null).statusCode()).isEqualTo(400);
    }
    @Test void settlementScopeIsEnforcedOverRealHttp() throws Exception {
        UUID other=UUID.randomUUID(),run=UUID.randomUUID(),rule=UUID.randomUUID();
        jdbc.sql("INSERT INTO org_unit(id,type,name) VALUES (:id,'DEPARTMENT','隔离封存部门')").param("id",other).update();
        seed("SCOPE_SUPERVISOR",other,"SUPERVISOR");seed("SCOPE_TECH",other,"TECHNICIAN");
        UUID supervisor=jdbc.sql("SELECT id FROM app_user WHERE employee_no='SCOPE_SUPERVISOR'").query(UUID.class).single();
        UUID target=jdbc.sql("SELECT id FROM app_user WHERE employee_no='SCOPE_TECH'").query(UUID.class).single();
        jdbc.sql("INSERT INTO rule_version(id,version,status,effective_at) VALUES (:id,'HTTP-SCOPE','PUBLISHED','2020-01-01')").param("id",rule).update();
        jdbc.sql("INSERT INTO settlement_run(id,period,org_unit_id,settlement_version,rule_version_id,status,created_by) VALUES (:id,'2024-01',:org,'HTTP',:rule,'PUBLISHED',:actor)").param("id",run).param("org",other).param("rule",rule).param("actor",supervisor).update();
        jdbc.sql("INSERT INTO settlement_candidate(run_id,user_id,total,base,bonus,penalty,proposed_grade,rank_no) VALUES (:run,:user,0,0,0,0,'B2',1)").param("run",run).param("user",target).update();
        String foreign=tokens.get("SUPERVISOR");
        assertThat(request("GET","/settlements/"+run,foreign,null).statusCode()).isEqualTo(403);
        assertThat(request("GET","/settlements?period=2024-01",foreign,null).body()).doesNotContain(run.toString());
        assertThat(request("POST","/settlements/"+run+"/confirmations",foreign,Map.of()).statusCode()).isEqualTo(403);
        var body=Map.of("runId",run,"userId",target,"adjustmentScore",2,"reason","隔离验证");
        assertThat(request("POST","/settlement-corrections",foreign,body).statusCode()).isEqualTo(403);
        String own=mapper.readTree(login("SCOPE_SUPERVISOR",password,"MINIPROGRAM").body()).path("data").path("accessToken").asText();
        var created=request("POST","/settlement-corrections",own,body);
        assertThat(created.statusCode()).isEqualTo(200);
        String correction=mapper.readTree(created.body()).path("data").path("id").asText();
        assertThat(mapper.readTree(created.body()).path("data").path("requiredApprovals").asInt()).isEqualTo(1);
        assertThat(request("GET","/settlement-corrections?period=2024-01",foreign,null).body()).doesNotContain(correction);
        assertThat(request("POST","/settlement-corrections/"+correction+"/votes",foreign,Map.of("decision","REJECT")).statusCode()).isEqualTo(403);
        assertThat(request("POST","/settlement-corrections",own,Map.of("adjustmentScore",2,"reason","missing IDs")).statusCode()).isEqualTo(400);
    }

    @AfterAll void stop() throws Exception {
        try {
            if(context!=null && Boolean.getBoolean("acceptance.browser")) {
                var marker=java.nio.file.Path.of("target/acceptance-browser-stop");
                java.nio.file.Files.deleteIfExists(marker);
                java.nio.file.Files.writeString(java.nio.file.Path.of("target/acceptance-api-url.txt"),base);
                System.out.println("BROWSER_ACCEPTANCE_READY "+base);
                long end=System.nanoTime()+java.time.Duration.ofMinutes(10).toNanos();
                while(!java.nio.file.Files.exists(marker) && System.nanoTime()<end)Thread.sleep(500);
            }
        } finally {if(context!=null)context.close();if(postgres!=null)postgres.close();}
    }
}
