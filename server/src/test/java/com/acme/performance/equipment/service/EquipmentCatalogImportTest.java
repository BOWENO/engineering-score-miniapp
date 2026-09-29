package com.acme.performance.equipment.service;

import com.acme.performance.admin.service.AdminGuard;
import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.organization.service.DirectoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EquipmentCatalogImportTest {

    @Test
    void importCreatesMissingLineAndStationThenUpdatesExistingEquipment() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            JdbcClient jdbc = JdbcClient.create(dataSource);
            UUID teamId = UUID.randomUUID();
            jdbc.sql("INSERT INTO org_unit(id,type,name,is_review_data) VALUES (:id,'TEAM','SMT',FALSE)")
                    .param("id", teamId).update();

            var actor = new CurrentUser(UUID.randomUUID(), "ADMIN-TEST", "内部管理账号",
                    teamId, Set.of(), false, true, false);
            jdbc.sql("""
                    INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status,is_administrator)
                    VALUES (:id,:employeeNo,:displayName,:teamId,'ACTIVE',TRUE)
                    """).param("id", actor.userId()).param("employeeNo", actor.employeeNo())
                    .param("displayName", actor.displayName()).param("teamId", teamId).update();
            var service = new EquipmentService(jdbc, new AdminGuard(), mock(AuditLogService.class), new ObjectMapper());

            EquipmentService.ImportResult first = service.importEquipment(actor,
                    equipmentWorkbook("回流线", "AOI站", "AOI-001", "AOI检测机"), "request-1");

            assertThat(first).isEqualTo(new EquipmentService.ImportResult(1, 0, 1, 1));
            assertThat(jdbc.sql("SELECT COUNT(*) FROM production_line WHERE org_unit_id=:teamId AND name='回流线'")
                    .param("teamId", teamId).query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM station WHERE name='AOI站'").query(Long.class).single()).isEqualTo(1);

            EquipmentService.ImportResult second = service.importEquipment(actor,
                    equipmentWorkbook("回流线", "AOI站", "AOI-001", "AOI检测机-更新"), "request-2");

            assertThat(second).isEqualTo(new EquipmentService.ImportResult(0, 1, 0, 0));
            assertThat(jdbc.sql("SELECT name FROM equipment WHERE code='AOI-001'").query(String.class).single())
                    .isEqualTo("AOI检测机-更新");

            UUID viewerId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status,is_administrator)
                    VALUES (:id,'SUPERVISOR-TEST','业务查看账号',:teamId,'ACTIVE',FALSE)
                    """).param("id", viewerId).param("teamId", teamId).update();
            jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:userId,'SUPERVISOR',:teamId)")
                    .param("id", UUID.randomUUID()).param("userId", viewerId).param("teamId", teamId).update();
            var viewer = new CurrentUser(viewerId, "SUPERVISOR-TEST", "业务查看账号",
                    teamId, Set.of("SUPERVISOR"), false);

            DirectoryService.Person masked = new DirectoryService(jdbc).people(viewer).stream()
                    .filter(person -> person.id().equals(actor.userId())).findFirst().orElseThrow();
            assertThat(masked.name()).isEqualTo("系统管理员");
            assertThat(masked.employeeNo()).isEqualTo("—");
        }
    }

    private MockMultipartFile equipmentWorkbook(String line, String station, String code, String name) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("设备台账");
            var header = sheet.createRow(0);
            String[] columns = {"设备编号", "设备名称", "设备类别", "班组", "线体", "站位", "状态"};
            for (int index = 0; index < columns.length; index++) header.createCell(index).setCellValue(columns[index]);
            var row = sheet.createRow(1);
            String[] values = {code, name, "AOI", "SMT", line, station, "启用"};
            for (int index = 0; index < values.length; index++) row.createCell(index).setCellValue(values[index]);
            workbook.write(output);
            return new MockMultipartFile("file", "equipment.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
