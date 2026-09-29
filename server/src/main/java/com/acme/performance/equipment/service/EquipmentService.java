package com.acme.performance.equipment.service;

import com.acme.performance.admin.service.AdminGuard;
import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class EquipmentService {
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{2,64}");
    private final JdbcClient jdbc;
    private final AdminGuard guard;
    private final AuditLogService audit;
    private final ObjectMapper mapper;

    public EquipmentService(JdbcClient jdbc, AdminGuard guard, AuditLogService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<EquipmentView> active(CurrentUser actor) {
        return query("WHERE e.status='ACTIVE' AND e.is_review_data=" + (isReview(actor) ? "TRUE" : "FALSE"));
    }

    @Transactional(readOnly = true)
    public List<EquipmentView> all(CurrentUser actor) {
        guard.requireSystemAdmin(actor);
        return query("WHERE e.is_review_data=FALSE");
    }

    @Transactional
    public EquipmentView create(CurrentUser actor, String code, String name, String category,
                                UUID orgUnitId, UUID lineId, UUID stationId, String requestId) {
        guard.requireSystemAdmin(actor);
        validate(code, name, category, orgUnitId, lineId, stationId, "ACTIVE");
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO equipment(id,code,name,category,org_unit_id,line_id,station_id,status)
                    VALUES (:id,:code,:name,:category,:orgUnitId,:lineId,:stationId,'ACTIVE')
                    """).param("id", id).param("code", code.trim()).param("name", name.trim())
                    .param("category", category.trim()).param("orgUnitId", orgUnitId)
                    .param("lineId", lineId).param("stationId", stationId).update();
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("EQUIPMENT_CODE_EXISTS", "设备编号已经存在", HttpStatus.CONFLICT);
        }
        EquipmentView result = get(id);
        audit.record(actor.userId(), "EQUIPMENT_CREATE", "EQUIPMENT", id, json(result), requestId);
        return result;
    }

    @Transactional
    public EquipmentView update(CurrentUser actor, UUID id, String code, String name, String category,
                                UUID orgUnitId, UUID lineId, UUID stationId, String status, long version, String requestId) {
        guard.requireSystemAdmin(actor);
        String normalized = status == null ? "" : status.toUpperCase();
        validate(code, name, category, orgUnitId, lineId, stationId, normalized);
        try {
            int updated = jdbc.sql("""
                    UPDATE equipment SET code=:code,name=:name,category=:category,org_unit_id=:orgUnitId,
                      line_id=:lineId,station_id=:stationId,status=:status,
                      disabled_at=CASE WHEN :status='DISABLED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                      version=version+1,updated_at=CURRENT_TIMESTAMP
                    WHERE id=:id AND version=:version
                    """).param("code", code.trim()).param("name", name.trim()).param("category", category.trim())
                    .param("orgUnitId", orgUnitId).param("lineId", lineId).param("stationId", stationId)
                    .param("status", normalized).param("id", id).param("version", version).update();
            if (updated != 1) {
                Long exists = jdbc.sql("SELECT COUNT(*) FROM equipment WHERE id=:id").param("id", id).query(Long.class).single();
                if (exists == 0) throw new ApiException("EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND);
                throw new ApiException("VERSION_CONFLICT", "设备信息已被其他操作更新", HttpStatus.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("EQUIPMENT_CODE_EXISTS", "设备编号已经存在", HttpStatus.CONFLICT);
        }
        EquipmentView result = get(id);
        audit.record(actor.userId(), "EQUIPMENT_UPDATE", "EQUIPMENT", id, json(result), requestId);
        return result;
    }

    @Transactional
    public ImportResult importEquipment(CurrentUser actor, MultipartFile file, String requestId) {
        guard.requireSystemAdmin(actor);
        if (file.isEmpty() || file.getSize() > 5L * 1024 * 1024) {
            throw new ApiException("INVALID_IMPORT_FILE", "Excel文件必须大于0且不超过5MB", HttpStatus.BAD_REQUEST);
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx") && !filename.endsWith(".xls")) {
            throw new ApiException("INVALID_IMPORT_FILE", "设备台账仅支持.xlsx或.xls文件", HttpStatus.BAD_REQUEST);
        }
        int inserted = 0;
        int updated = 0;
        int linesCreated = 0;
        int stationsCreated = 0;
        try {
            List<ImportRow> rows = readExcel(file);
            if (rows.isEmpty()) throw new ApiException("INVALID_IMPORT_FILE", "导入文件没有设备数据", HttpStatus.BAD_REQUEST);
            int rowNumber = 1;
            for (ImportRow row : rows) {
                rowNumber++;
                String status = normalizeStatus(row.status(), rowNumber);
                Mapping mapping = resolveMapping(row, rowNumber);
                if (mapping.lineCreated()) linesCreated++;
                if (mapping.stationCreated()) stationsCreated++;
                validate(row.code(), row.name(), row.category(), mapping.orgId(), mapping.lineId(), mapping.stationId(), status);
                UUID existingId = jdbc.sql("SELECT id FROM equipment WHERE code=:code AND is_review_data=FALSE")
                        .param("code", row.code().trim()).query(UUID.class).optional().orElse(null);
                if (existingId == null) {
                    UUID id = UUID.randomUUID();
                    jdbc.sql("""
                            INSERT INTO equipment(id,code,name,category,org_unit_id,line_id,station_id,status,disabled_at)
                            VALUES (:id,:code,:name,:category,:orgId,:lineId,:stationId,:status,
                              CASE WHEN :status='DISABLED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                            """).param("id", id).param("code", row.code().trim()).param("name", row.name().trim())
                            .param("category", row.category().trim()).param("orgId", mapping.orgId())
                            .param("lineId", mapping.lineId()).param("stationId", mapping.stationId())
                            .param("status", status).update();
                    inserted++;
                } else {
                    jdbc.sql("""
                            UPDATE equipment SET name=:name,category=:category,org_unit_id=:orgId,line_id=:lineId,
                              station_id=:stationId,status=:status,
                              disabled_at=CASE WHEN :status='DISABLED' THEN COALESCE(disabled_at,CURRENT_TIMESTAMP) ELSE NULL END,
                              version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id
                            """).param("name", row.name().trim()).param("category", row.category().trim())
                            .param("orgId", mapping.orgId()).param("lineId", mapping.lineId())
                            .param("stationId", mapping.stationId()).param("status", status).param("id", existingId).update();
                    updated++;
                }
            }
            ImportResult result = new ImportResult(inserted, updated, linesCreated, stationsCreated);
            audit.record(actor.userId(), "EQUIPMENT_IMPORT", "EQUIPMENT", null, json(result), requestId);
            return result;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("INVALID_IMPORT_FILE", "Excel格式错误，或内容无法读取", HttpStatus.BAD_REQUEST);
        }
    }

    private List<ImportRow> readExcel(MultipartFile file) throws Exception {
        List<ImportRow> result = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (InputStream input = file.getInputStream(); var workbook = WorkbookFactory.create(input)) {
            var sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() == 0) return result;
            Map<String, Integer> columns = new LinkedHashMap<>();
            var header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) return result;
            for (var cell : header) columns.put(formatter.formatCellValue(cell).trim(), cell.getColumnIndex());
            for (int index = sheet.getFirstRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (row == null) continue;
                String code = excelOptional(row, columns, formatter, "设备编号", "code");
                if (code == null || code.isBlank()) continue;
                result.add(new ImportRow(code,
                        excelRequired(row, columns, formatter, "设备名称", "name"),
                        excelRequired(row, columns, formatter, "设备类别", "类别", "category"),
                        excelRequired(row, columns, formatter, "班组", "team"),
                        excelRequired(row, columns, formatter, "线体", "line"),
                        excelRequired(row, columns, formatter, "站位", "station"),
                        excelOptional(row, columns, formatter, "状态", "status")));
            }
        }
        return result;
    }

    private String excelRequired(org.apache.poi.ss.usermodel.Row row, Map<String, Integer> columns,
                                 DataFormatter formatter, String... names) {
        String value = excelOptional(row, columns, formatter, names);
        if (value == null || value.isBlank()) {
            throw new ApiException("INVALID_IMPORT_ROW", "导入文件缺少必填列或内容：" + String.join("/", names), HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private String excelOptional(org.apache.poi.ss.usermodel.Row row, Map<String, Integer> columns,
                                 DataFormatter formatter, String... names) {
        for (String name : names) {
            Integer index = columns.get(name);
            if (index != null) {
                String value = formatter.formatCellValue(row.getCell(index)).trim();
                if (!value.isBlank()) return value;
            }
        }
        return null;
    }

    private Mapping resolveMapping(ImportRow row, int rowNumber) {
        List<UUID> teams = jdbc.sql("""
                SELECT id FROM org_unit
                WHERE type='TEAM' AND is_review_data=FALSE AND lower(name)=lower(:team)
                """).param("team", row.team().trim()).query(UUID.class).list();
        if (teams.size() != 1) {
            throw new ApiException("INVALID_IMPORT_ROW", "第" + rowNumber + "行班组不存在或不唯一", HttpStatus.BAD_REQUEST);
        }
        UUID orgId = teams.getFirst();
        Lookup line = resolveLine(orgId, row.line().trim(), rowNumber);
        Lookup station = resolveStation(line.id(), row.station().trim(), rowNumber);
        return new Mapping(orgId, line.id(), station.id(), line.created(), station.created());
    }

    private Lookup resolveLine(UUID orgId, String value, int rowNumber) {
        List<UUID> matches = jdbc.sql("""
                SELECT id FROM production_line
                WHERE org_unit_id=:orgId AND is_review_data=FALSE
                  AND (lower(name)=lower(:value) OR lower(code)=lower(:value))
                """).param("orgId", orgId).param("value", value).query(UUID.class).list();
        if (matches.size() > 1) {
            throw new ApiException("INVALID_IMPORT_ROW", "第" + rowNumber + "行线体匹配不唯一", HttpStatus.BAD_REQUEST);
        }
        if (!matches.isEmpty()) return new Lookup(matches.getFirst(), false);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production_line(id,org_unit_id,code,name,status,is_review_data)
                VALUES (:id,:orgId,:code,:name,'ACTIVE',FALSE)
                """).param("id", id).param("orgId", orgId)
                .param("code", generatedCode("LINE", orgId, value)).param("name", value).update();
        return new Lookup(id, true);
    }

    private Lookup resolveStation(UUID lineId, String value, int rowNumber) {
        List<UUID> matches = jdbc.sql("""
                SELECT id FROM station
                WHERE line_id=:lineId AND (lower(name)=lower(:value) OR lower(code)=lower(:value))
                """).param("lineId", lineId).param("value", value).query(UUID.class).list();
        if (matches.size() > 1) {
            throw new ApiException("INVALID_IMPORT_ROW", "第" + rowNumber + "行站位匹配不唯一", HttpStatus.BAD_REQUEST);
        }
        if (!matches.isEmpty()) return new Lookup(matches.getFirst(), false);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO station(id,line_id,code,name,status)
                VALUES (:id,:lineId,:code,:name,'ACTIVE')
                """).param("id", id).param("lineId", lineId)
                .param("code", generatedCode("ST", lineId, value)).param("name", value).update();
        return new Lookup(id, true);
    }

    private String generatedCode(String prefix, UUID scopeId, String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((scopeId + ":" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            return prefix + "-" + HexFormat.of().formatHex(digest, 0, 6).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String normalizeStatus(String value, int rowNumber) {
        if (value == null || value.isBlank() || "启用".equals(value.trim())) return "ACTIVE";
        if ("停用".equals(value.trim())) return "DISABLED";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "DISABLED").contains(normalized)) {
            throw new ApiException("INVALID_IMPORT_ROW", "第" + rowNumber + "行状态只能为启用或停用", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private List<EquipmentView> query(String where) {
        return jdbc.sql("""
                SELECT e.id,e.code,e.name,e.category,e.org_unit_id,o.name AS org_name,e.line_id,l.name AS line_name,
                       e.station_id,s.name AS station_name,e.status,e.version
                FROM equipment e JOIN org_unit o ON o.id=e.org_unit_id
                LEFT JOIN production_line l ON l.id=e.line_id LEFT JOIN station s ON s.id=e.station_id
                """ + where + " ORDER BY e.category,e.code")
                .query((rs, n) -> new EquipmentView(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("category"), rs.getObject("org_unit_id", UUID.class),
                        rs.getString("org_name"),rs.getObject("line_id", UUID.class),rs.getString("line_name"),
                        rs.getObject("station_id", UUID.class),rs.getString("station_name"),
                        rs.getString("status"), rs.getLong("version"))).list();
    }

    private EquipmentView get(UUID id) {
        return jdbc.sql("""
                SELECT e.id,e.code,e.name,e.category,e.org_unit_id,o.name AS org_name,e.line_id,l.name AS line_name,
                       e.station_id,s.name AS station_name,e.status,e.version
                FROM equipment e JOIN org_unit o ON o.id=e.org_unit_id
                LEFT JOIN production_line l ON l.id=e.line_id LEFT JOIN station s ON s.id=e.station_id WHERE e.id=:id
                """).param("id", id).query((rs, n) -> new EquipmentView(rs.getObject("id", UUID.class),
                        rs.getString("code"), rs.getString("name"), rs.getString("category"),
                        rs.getObject("org_unit_id", UUID.class), rs.getString("org_name"),
                        rs.getObject("line_id", UUID.class),rs.getString("line_name"),
                        rs.getObject("station_id", UUID.class),rs.getString("station_name"),
                        rs.getString("status"), rs.getLong("version")))
                .optional().orElseThrow(() -> new ApiException("EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND));
    }

    private void validate(String code, String name, String category, UUID orgUnitId, UUID lineId, UUID stationId, String status) {
        if (code == null || !CODE.matcher(code).matches() || name == null || name.isBlank()
                || category == null || category.isBlank() || orgUnitId == null || lineId == null || stationId == null
                || !Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw new ApiException("INVALID_EQUIPMENT", "设备编号、名称、类别、班组或状态不合法", HttpStatus.BAD_REQUEST);
        }
        Long orgExists = jdbc.sql("SELECT COUNT(*) FROM org_unit WHERE id=:id").param("id", orgUnitId).query(Long.class).single();
        if (orgExists == 0) throw new ApiException("INVALID_EQUIPMENT_ORG", "设备所属班组不存在", HttpStatus.BAD_REQUEST);
        Long mapping = jdbc.sql("""
                SELECT COUNT(*) FROM station s JOIN production_line l ON l.id=s.line_id
                WHERE s.id=:stationId AND l.id=:lineId AND l.org_unit_id=:orgId
                """).param("stationId", stationId).param("lineId", lineId).param("orgId", orgUnitId).query(Long.class).single();
        if (mapping != 1) throw new ApiException("INVALID_EQUIPMENT_MAPPING", "班组、线体和站位对应关系不正确", HttpStatus.BAD_REQUEST);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { return "{}"; }
    }

    private boolean isReview(CurrentUser actor) { return "wxreview".equalsIgnoreCase(actor.employeeNo()); }

    public record EquipmentView(UUID id, String code, String name, String category, UUID orgUnitId,
                                String orgName, UUID lineId, String lineName, UUID stationId, String stationName,
                                String status, long version) {}
    public record ImportResult(int inserted, int updated, int linesCreated, int stationsCreated) {}
    private record ImportRow(String code, String name, String category, String team, String line,
                             String station, String status) {}
    private record Mapping(UUID orgId, UUID lineId, UUID stationId, boolean lineCreated, boolean stationCreated) {}
    private record Lookup(UUID id, boolean created) {}
}
