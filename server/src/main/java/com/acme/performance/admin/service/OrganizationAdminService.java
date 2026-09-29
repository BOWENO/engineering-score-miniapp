package com.acme.performance.admin.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.time.OffsetDateTime;
import com.acme.performance.auth.service.AdminAuthService;
import com.acme.performance.auth.service.TokenService;
import com.acme.performance.organization.model.BusinessRoles;

@Service
public class OrganizationAdminService {
    private static final Set<String> ORG_TYPES = Set.of("COMPANY", "DEPARTMENT", "TEAM");
    private static final Set<String> ROLES = BusinessRoles.ALL;
    private static final Pattern EMPLOYEE_NO = Pattern.compile("[A-Za-z0-9_-]{2,32}");
    private final JdbcClient jdbc;
    private final AdminGuard guard;
    private final AuditLogService audit;
    private final ObjectMapper mapper;
    private final AdminAuthService auth;
    private final TokenService tokens;

    public OrganizationAdminService(JdbcClient jdbc, AdminGuard guard, AuditLogService audit, ObjectMapper mapper,
                                    AdminAuthService auth, TokenService tokens) {
        this.jdbc = jdbc; this.guard = guard; this.audit = audit; this.mapper = mapper;
        this.auth = auth; this.tokens = tokens;
    }

    public List<OrgItem> organizations(CurrentUser user) {
        guard.requireAdministratorOrManager(user);
        return jdbc.sql("SELECT id,parent_id,type,name FROM org_unit WHERE is_review_data=FALSE ORDER BY created_at,name")
                .query((rs, n) -> new OrgItem(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                        rs.getString("type"), rs.getString("name"))).list();
    }

    public List<UserItem> users(CurrentUser user) {
        guard.requireSystemAdmin(user);
        return jdbc.sql("""
                SELECT u.id,u.employee_no,u.display_name,u.status,u.org_unit_id,o.name org_name,
                       u.openid IS NOT NULL wechat_bound,
                       COALESCE(ws.permission_status,'NOT_REQUESTED') wechat_notification_status,
                       COALESCE(ws.credits,0) wechat_notification_credits,
                       ws.updated_at wechat_notification_updated_at,
                       COALESCE(string_agg(DISTINCT rb.role_code, ',' ORDER BY rb.role_code),'') roles,
                       ac.username web_username,u.is_administrator,u.password_change_required,u.locked_until
                FROM app_user u JOIN org_unit o ON o.id=u.org_unit_id
                LEFT JOIN role_binding rb ON rb.user_id=u.id
                LEFT JOIN admin_credential ac ON ac.user_id=u.id
                LEFT JOIN LATERAL (
                    SELECT permission_status,credits,updated_at FROM wechat_subscription_credit
                    WHERE user_id=u.id ORDER BY updated_at DESC LIMIT 1
                ) ws ON TRUE
                WHERE u.is_review_account=FALSE AND upper(u.employee_no)<>'ADMIN001'
                GROUP BY u.id,u.employee_no,u.display_name,u.status,u.org_unit_id,o.name,u.openid,
                         ws.permission_status,ws.credits,ws.updated_at,ac.username,
                         u.is_administrator,u.password_change_required,u.locked_until
                ORDER BY u.employee_no LIMIT 500
                """).query((rs, n) -> new UserItem(
                        rs.getObject("id", UUID.class), rs.getString("employee_no"), rs.getString("display_name"),
                        rs.getString("status"), rs.getObject("org_unit_id", UUID.class), rs.getString("org_name"),
                        rs.getBoolean("wechat_bound"), rs.getString("wechat_notification_status"),
                        rs.getInt("wechat_notification_credits"),rs.getObject("wechat_notification_updated_at", OffsetDateTime.class),
                        rs.getString("roles").isBlank() ? List.of() : List.of(rs.getString("roles").split(",")),
                        rs.getString("web_username"),rs.getBoolean("is_administrator"),
                        rs.getBoolean("password_change_required"),rs.getObject("locked_until", OffsetDateTime.class)
                )).list();
    }

    @Transactional
    public OrgItem createOrganization(CurrentUser actor, UUID parentId, String type, String name, String requestId) {
        guard.requireSystemAdmin(actor);
        String normalized = type == null ? "" : type.toUpperCase();
        if (!ORG_TYPES.contains(normalized) || name == null || name.isBlank()) {
            throw new ApiException("INVALID_ORGANIZATION", "组织类型或名称不合法", HttpStatus.BAD_REQUEST);
        }
        if (parentId != null && !exists("SELECT COUNT(*) FROM org_unit WHERE id=:id", parentId)) {
            throw new ApiException("PARENT_ORG_NOT_FOUND", "上级组织不存在", HttpStatus.BAD_REQUEST);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO org_unit(id,parent_id,type,name) VALUES (:id,:parentId,:type,:name)")
                .param("id", id).param("parentId", parentId).param("type", normalized).param("name", name.trim()).update();
        audit.record(actor.userId(), "ORG_CREATE", "ORG_UNIT", id, json(new OrgItem(id, parentId, normalized, name.trim())), requestId);
        return new OrgItem(id, parentId, normalized, name.trim());
    }

    @Transactional
    public OrgItem updateOrganization(CurrentUser actor, UUID id, UUID parentId, String type, String name, String requestId) {
        guard.requireSystemAdmin(actor);
        String normalized = type == null ? "" : type.toUpperCase();
        if (!ORG_TYPES.contains(normalized) || name == null || name.isBlank() || id.equals(parentId))
            throw new ApiException("INVALID_ORGANIZATION", "组织类型、名称或上级组织不合法", HttpStatus.BAD_REQUEST);
        if (parentId != null && !exists("SELECT COUNT(*) FROM org_unit WHERE id=:id", parentId))
            throw new ApiException("PARENT_ORG_NOT_FOUND", "上级组织不存在", HttpStatus.BAD_REQUEST);
        int updated = jdbc.sql("UPDATE org_unit SET parent_id=:parentId,type=:type,name=:name WHERE id=:id")
                .param("parentId", parentId).param("type", normalized).param("name", name.trim()).param("id", id).update();
        if (updated != 1) throw new ApiException("ORG_NOT_FOUND", "组织不存在", HttpStatus.NOT_FOUND);
        OrgItem result = new OrgItem(id, parentId, normalized, name.trim());
        audit.record(actor.userId(), "ORG_UPDATE", "ORG_UNIT", id, json(result), requestId);
        return result;
    }

    @Transactional
    public UUID createUser(CurrentUser actor, String employeeNo, String displayName, UUID orgId, String requestId) {
        guard.requireSystemAdmin(actor);
        if (employeeNo == null || !EMPLOYEE_NO.matcher(employeeNo).matches() || displayName == null || displayName.isBlank()
                || orgId == null || !exists("SELECT COUNT(*) FROM org_unit WHERE id=:id AND is_review_data=FALSE", orgId))
            throw new ApiException("INVALID_USER", "工号、姓名或所属组织不合法", HttpStatus.BAD_REQUEST);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status) VALUES (:id,:employeeNo,:name,:orgId,'ACTIVE')")
                    .param("id", id).param("employeeNo", employeeNo).param("name", displayName.trim()).param("orgId", orgId).update();
            jdbc.sql("INSERT INTO admin_credential(user_id,username,password_hash) VALUES (:userId,:username,:hash)")
                    .param("userId", id).param("username", employeeNo).param("hash", auth.encodePassword(employeeNo)).update();
        } catch (DataIntegrityViolationException ex) { throw new ApiException("EMPLOYEE_NO_EXISTS", "工号已经存在", HttpStatus.CONFLICT); }
        audit.record(actor.userId(), "USER_CREATE", "APP_USER", id, json(java.util.Map.of("employeeNo", employeeNo)), requestId);
        return id;
    }

    @Transactional
    public void updateUser(CurrentUser actor, UUID userId, String displayName, UUID orgId, String status, String requestId) {
        guard.requireSystemAdmin(actor);
        String normalized = status == null ? "" : status.toUpperCase();
        if (displayName == null || displayName.isBlank() || orgId == null || !Set.of("ACTIVE", "DISABLED").contains(normalized)
                || !exists("SELECT COUNT(*) FROM org_unit WHERE id=:id AND is_review_data=FALSE", orgId))
            throw new ApiException("INVALID_USER", "姓名、组织或状态不合法", HttpStatus.BAD_REQUEST);
        if (actor.userId().equals(userId) && normalized.equals("DISABLED"))
            throw new ApiException("CANNOT_DISABLE_SELF", "不能停用当前登录管理员", HttpStatus.CONFLICT);
        int updated = jdbc.sql("UPDATE app_user SET display_name=:name,org_unit_id=:orgId,status=:status,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("name", displayName.trim()).param("orgId", orgId).param("status", normalized).param("id", userId).update();
        if (updated != 1) throw new ApiException("USER_NOT_FOUND", "员工不存在", HttpStatus.NOT_FOUND);
        if (normalized.equals("DISABLED")) jdbc.sql("UPDATE access_token SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=:id AND revoked_at IS NULL").param("id", userId).update();
        audit.record(actor.userId(), "USER_UPDATE", "APP_USER", userId, json(java.util.Map.of("status", normalized)), requestId);
    }

    @Transactional
    public ImportResult importUsers(CurrentUser actor, MultipartFile file, String requestId) {
        guard.requireSystemAdmin(actor);
        if (file.isEmpty() || file.getSize() > 5L * 1024 * 1024) {
            throw new ApiException("INVALID_IMPORT_FILE", "Excel或CSV文件必须大于0且不超过5MB", HttpStatus.BAD_REQUEST);
        }
        int inserted = 0, updated = 0;
        try {
            String filename=file.getOriginalFilename()==null?"":file.getOriginalFilename().toLowerCase(Locale.ROOT);
            List<ImportRow> rows=(filename.endsWith(".xlsx")||filename.endsWith(".xls"))?readExcel(file):readCsv(file);
            if(rows.isEmpty())throw new ApiException("INVALID_IMPORT_FILE","导入文件没有人员数据",HttpStatus.BAD_REQUEST);
            int rowNumber=1;
            for(ImportRow row:rows){rowNumber++;String employeeNo=row.employeeNo().trim(),displayName=row.displayName().trim();String status=status(row.status());UUID orgId=resolveOrg(row,rowNumber);String role=role(row.role(),rowNumber);
                if(!EMPLOYEE_NO.matcher(employeeNo).matches()||displayName.isBlank())throw new ApiException("INVALID_IMPORT_ROW","第"+rowNumber+"行工号或姓名不合法",HttpStatus.BAD_REQUEST);
                UUID userId=jdbc.sql("SELECT id FROM app_user WHERE employee_no=:employeeNo").param("employeeNo",employeeNo).query(UUID.class).optional().orElse(null);
                if(userId==null){userId=UUID.randomUUID();jdbc.sql("INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status,password_change_required) VALUES (:id,:employeeNo,:name,:orgId,:status,TRUE)").param("id",userId).param("employeeNo",employeeNo).param("name",displayName).param("orgId",orgId).param("status",status).update();jdbc.sql("INSERT INTO admin_credential(user_id,username,password_hash) VALUES (:userId,:username,:hash)").param("userId",userId).param("username",employeeNo).param("hash",auth.encodePassword(employeeNo)).update();inserted++;}
                else{jdbc.sql("UPDATE app_user SET display_name=:name,org_unit_id=:orgId,status=:status,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("name",displayName).param("orgId",orgId).param("status",status).param("id",userId).update();updated++;}
                if(row.role()!=null&&!row.role().isBlank()){jdbc.sql("DELETE FROM role_binding WHERE user_id=:userId AND role_code IN ('TECHNICIAN','ASSISTANT_ENGINEER','SUPERVISOR','DEPARTMENT_MANAGER')").param("userId",userId).update();if(role!=null)jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:userId,:role,:scopeId)").param("id",UUID.randomUUID()).param("userId",userId).param("role",role).param("scopeId",orgId).update();}
                if("DISABLED".equals(status))tokens.revokeAll(userId);
            }
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) { throw new ApiException("INVALID_IMPORT_FILE", "Excel或CSV格式错误，或内容无法读取", HttpStatus.BAD_REQUEST); }
        ImportResult result = new ImportResult(inserted, updated);
        audit.record(actor.userId(), "USER_IMPORT", "APP_USER", null, json(result), requestId);
        return result;
    }

    @Transactional
    public void bindWechat(CurrentUser actor, UUID userId, String openid, String requestId) {
        guard.requireSystemAdmin(actor);
        if (openid == null || openid.isBlank() || openid.length() > 128) {
            throw new ApiException("INVALID_OPENID", "openid不合法", HttpStatus.BAD_REQUEST);
        }
        try {
            int updated = jdbc.sql("UPDATE app_user SET openid=:openid,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                    .param("openid", openid).param("id", userId).update();
            if (updated != 1) throw new ApiException("USER_NOT_FOUND", "员工不存在", HttpStatus.NOT_FOUND);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("OPENID_ALREADY_BOUND", "该微信身份已绑定其他员工", HttpStatus.CONFLICT);
        }
        audit.record(actor.userId(), "WECHAT_BIND", "APP_USER", userId, "{\"openid\":\"***\"}", requestId);
    }

    @Transactional
    public void unbindWechat(CurrentUser actor, UUID userId, String requestId) {
        guard.requireSystemAdmin(actor);
        int updated = jdbc.sql("UPDATE app_user SET openid=NULL,unionid=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("id", userId).update();
        if (updated != 1) throw new ApiException("USER_NOT_FOUND", "员工不存在", HttpStatus.NOT_FOUND);
        tokens.revokeAll(userId);
        audit.record(actor.userId(), "WECHAT_UNBIND", "APP_USER", userId, "{}", requestId);
    }

    @Transactional
    public UUID grantRole(CurrentUser actor, UUID userId, String roleCode, UUID scopeId, String requestId) {
        guard.requireSystemAdmin(actor);
        String role = roleCode == null ? "" : roleCode.toUpperCase();
        if (!ROLES.contains(role)) throw new ApiException("INVALID_ROLE", "角色代码不合法", HttpStatus.BAD_REQUEST);
        if (!exists("SELECT COUNT(*) FROM app_user WHERE id=:id AND is_review_account=FALSE", userId) || (scopeId != null && !exists("SELECT COUNT(*) FROM org_unit WHERE id=:id AND is_review_data=FALSE", scopeId))) {
            throw new ApiException("INVALID_ROLE_BINDING", "用户或授权范围不存在", HttpStatus.BAD_REQUEST);
        }
        UUID id = jdbc.sql("SELECT id FROM role_binding WHERE user_id=:userId AND role_code=:role AND scope_id IS NOT DISTINCT FROM :scopeId")
                .param("userId", userId).param("role", role).param("scopeId", scopeId).query(UUID.class).optional()
                .orElseGet(() -> {
                    UUID created = UUID.randomUUID();
                    jdbc.sql("INSERT INTO role_binding(id,user_id,role_code,scope_id) VALUES (:id,:userId,:role,:scopeId)")
                            .param("id", created).param("userId", userId).param("role", role).param("scopeId", scopeId).update();
                    return created;
                });
        audit.record(actor.userId(), "ROLE_GRANT", "ROLE_BINDING", id, json(java.util.Map.of("role", role)), requestId);
        return id;
    }

    @Transactional
    public void revokeRole(CurrentUser actor, UUID userId, String roleCode, UUID scopeId, String requestId) {
        guard.requireSystemAdmin(actor);
        String role = roleCode == null ? "" : roleCode.toUpperCase();
        if (!ROLES.contains(role)) throw new ApiException("INVALID_ROLE", "角色代码不合法", HttpStatus.BAD_REQUEST);
        jdbc.sql("DELETE FROM role_binding WHERE user_id=:userId AND role_code=:role AND scope_id IS NOT DISTINCT FROM :scopeId")
                .param("userId", userId).param("role", role).param("scopeId", scopeId).update();
        audit.record(actor.userId(), "ROLE_REVOKE", "ROLE_BINDING", null, json(java.util.Map.of("role", role)), requestId);
    }

    private boolean exists(String sql, UUID id) { return jdbc.sql(sql).param("id", id).query(Long.class).single() > 0; }

    @Transactional
    public void resetCredential(CurrentUser actor, UUID userId, String requestId) {
        guard.requireSystemAdmin(actor);
        String employeeNo = jdbc.sql("SELECT employee_no FROM app_user WHERE id=:id")
                .param("id", userId).query(String.class).optional()
                .orElseThrow(() -> new ApiException("USER_NOT_FOUND", "员工不存在", HttpStatus.NOT_FOUND));
        int updated = jdbc.sql("UPDATE admin_credential SET username=:username,password_hash=:hash,updated_at=CURRENT_TIMESTAMP WHERE user_id=:id")
                .param("username", employeeNo).param("hash", auth.encodePassword(employeeNo)).param("id", userId).update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO admin_credential(user_id,username,password_hash) VALUES (:id,:username,:hash)")
                    .param("id", userId).param("username", employeeNo).param("hash", auth.encodePassword(employeeNo)).update();
        }
        jdbc.sql("UPDATE app_user SET password_change_required=TRUE,failed_login_count=0,locked_until=NULL WHERE id=:id")
                .param("id", userId).update();
        tokens.revokeAll(userId);
        audit.record(actor.userId(), "CREDENTIAL_RESET", "APP_USER", userId, "{}", requestId);
    }

    @Transactional
    public void unlock(CurrentUser actor, UUID userId, String requestId) {
        guard.requireSystemAdmin(actor);
        int updated = jdbc.sql("UPDATE app_user SET failed_login_count=0,locked_until=NULL WHERE id=:id")
                .param("id", userId).update();
        if (updated != 1) throw new ApiException("USER_NOT_FOUND", "员工不存在", HttpStatus.NOT_FOUND);
        audit.record(actor.userId(), "ACCOUNT_UNLOCK", "APP_USER", userId, "{}", requestId);
    }
    private List<ImportRow> readCsv(MultipartFile file)throws Exception{List<ImportRow> result=new ArrayList<>();try(var reader=new InputStreamReader(file.getInputStream(),StandardCharsets.UTF_8)){Iterable<CSVRecord> records=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).setTrim(true).build().parse(reader);for(CSVRecord row:records)result.add(new ImportRow(csv(row,"employee_no","工号"),csv(row,"display_name","姓名"),csvOptional(row,"status","状态"),csvOptional(row,"role","岗位"),csvOptional(row,"team","班组"),csvOptional(row,"org_unit_id","组织ID")));}return result;}
    private List<ImportRow> readExcel(MultipartFile file)throws Exception{List<ImportRow> result=new ArrayList<>();DataFormatter formatter=new DataFormatter();try(InputStream input=file.getInputStream();var workbook=WorkbookFactory.create(input)){var sheet=workbook.getSheetAt(0);if(sheet.getPhysicalNumberOfRows()==0)return result;Map<String,Integer> columns=new LinkedHashMap<>();for(var cell:sheet.getRow(sheet.getFirstRowNum()))columns.put(formatter.formatCellValue(cell).trim(),cell.getColumnIndex());for(int i=sheet.getFirstRowNum()+1;i<=sheet.getLastRowNum();i++){var row=sheet.getRow(i);if(row==null)continue;String employee=excel(row,columns,formatter,"employee_no","工号");if(employee.isBlank())continue;result.add(new ImportRow(employee,excel(row,columns,formatter,"display_name","姓名"),excelOptional(row,columns,formatter,"status","状态"),excelOptional(row,columns,formatter,"role","岗位"),excelOptional(row,columns,formatter,"team","班组"),excelOptional(row,columns,formatter,"org_unit_id","组织ID")));}}return result;}
    private String csv(CSVRecord row,String...names){String value=csvOptional(row,names);if(value==null||value.isBlank())throw new ApiException("INVALID_IMPORT_ROW","导入文件缺少必填列或内容："+String.join("/",names),HttpStatus.BAD_REQUEST);return value;}
    private String csvOptional(CSVRecord row,String...names){for(String name:names)if(row.isMapped(name)&&!row.get(name).isBlank())return row.get(name).trim();return null;}
    private String excel(org.apache.poi.ss.usermodel.Row row,Map<String,Integer> columns,DataFormatter formatter,String...names){String value=excelOptional(row,columns,formatter,names);if(value==null||value.isBlank())throw new ApiException("INVALID_IMPORT_ROW","导入文件缺少必填列或内容："+String.join("/",names),HttpStatus.BAD_REQUEST);return value;}
    private String excelOptional(org.apache.poi.ss.usermodel.Row row,Map<String,Integer> columns,DataFormatter formatter,String...names){for(String name:names){Integer index=columns.get(name);if(index!=null){String value=formatter.formatCellValue(row.getCell(index)).trim();if(!value.isBlank())return value;}}return null;}
    private UUID resolveOrg(ImportRow row,int number){if(row.orgUnitId()!=null&&!row.orgUnitId().isBlank())try{UUID id=UUID.fromString(row.orgUnitId());if(exists("SELECT COUNT(*) FROM org_unit WHERE id=:id AND is_review_data=FALSE",id))return id;}catch(IllegalArgumentException ignored){}if(row.team()!=null&&!row.team().isBlank())return jdbc.sql("SELECT id FROM org_unit WHERE type IN ('TEAM','DEPARTMENT') AND is_review_data=FALSE AND lower(name)=lower(:name) ORDER BY CASE type WHEN 'TEAM' THEN 0 ELSE 1 END LIMIT 1").param("name",row.team().trim()).query(UUID.class).optional().orElseThrow(()->new ApiException("INVALID_IMPORT_ROW","第"+number+"行班组或组织不存在",HttpStatus.BAD_REQUEST));throw new ApiException("INVALID_IMPORT_ROW","第"+number+"行缺少班组或组织ID",HttpStatus.BAD_REQUEST);}
    private String status(String value){if(value==null||value.isBlank()||"启用".equals(value))return "ACTIVE";if("停用".equals(value))return "DISABLED";String normalized=value.toUpperCase(Locale.ROOT);if(!Set.of("ACTIVE","DISABLED").contains(normalized))throw new ApiException("INVALID_IMPORT_ROW","人员状态只能是启用或停用",HttpStatus.BAD_REQUEST);return normalized;}
    private String role(String value,int number){if(value==null||value.isBlank())return null;String normalized=switch(value.trim()){case "技术员"->BusinessRoles.TECHNICIAN;case "助理工程师"->BusinessRoles.ASSISTANT_ENGINEER;case "主管"->BusinessRoles.SUPERVISOR;case "部长","经理"->BusinessRoles.DEPARTMENT_MANAGER;case "事务员","无业务权限"->null;default->value.trim().toUpperCase(Locale.ROOT);};if(normalized!=null&&!BusinessRoles.ALL.contains(normalized))throw new ApiException("INVALID_IMPORT_ROW","第"+number+"行岗位不属于现有四种业务角色",HttpStatus.BAD_REQUEST);return normalized;}
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { return "{}"; } }

    public record OrgItem(UUID id, UUID parentId, String type, String name) {}
    public record UserItem(UUID id, String employeeNo, String displayName, String status, UUID orgUnitId,
                           String orgName, boolean wechatBound, String wechatNotificationStatus,
                           int wechatNotificationCredits, OffsetDateTime wechatNotificationUpdatedAt,
                           List<String> roles, String webUsername,
                           boolean administrator, boolean passwordChangeRequired, OffsetDateTime lockedUntil) {}
    public record ImportResult(int inserted, int updated) {}
    private record ImportRow(String employeeNo,String displayName,String status,String role,String team,String orgUnitId) {}
}
