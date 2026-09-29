package com.acme.performance.equipment.service;

import com.acme.performance.admin.service.AdminGuard;
import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CatalogService {
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final JdbcClient jdbc;
    private final AdminGuard guard;
    private final AuditLogService audit;

    public CatalogService(JdbcClient jdbc, AdminGuard guard, AuditLogService audit) {
        this.jdbc = jdbc; this.guard = guard; this.audit = audit;
    }

    @Transactional(readOnly = true)
    public CatalogView active(CurrentUser actor) { return catalog("ACTIVE", isReview(actor)); }

    @Transactional(readOnly = true)
    public CatalogView all(CurrentUser actor) {
        guard.requireAdministratorOrManager(actor);
        return catalog(null, false);
    }

    @Transactional
    public LineView createLine(CurrentUser actor, UUID teamId, String code, String name, String requestId) {
        guard.requireSystemAdmin(actor); validate(code,name);
        Long team = jdbc.sql("SELECT COUNT(*) FROM org_unit WHERE id=:id AND type='TEAM'").param("id",teamId).query(Long.class).single();
        if (team != 1) throw new ApiException("TEAM_NOT_FOUND", "班组不存在", HttpStatus.BAD_REQUEST);
        UUID id=UUID.randomUUID();
        try { jdbc.sql("INSERT INTO production_line(id,org_unit_id,code,name,status) VALUES (:id,:teamId,:code,:name,'ACTIVE')")
                .param("id",id).param("teamId",teamId).param("code",code.trim()).param("name",name.trim()).update(); }
        catch (DataIntegrityViolationException ex) { throw new ApiException("LINE_CODE_EXISTS","班组内线体编号已存在",HttpStatus.CONFLICT); }
        LineView result=line(id); audit.record(actor.userId(),"LINE_CREATE","PRODUCTION_LINE",id,"{}",requestId); return result;
    }

    @Transactional
    public StationView createStation(CurrentUser actor, UUID lineId, String code, String name, String requestId) {
        guard.requireSystemAdmin(actor); validate(code,name);
        Long line=jdbc.sql("SELECT COUNT(*) FROM production_line WHERE id=:id AND status='ACTIVE'").param("id",lineId).query(Long.class).single();
        if(line!=1) throw new ApiException("LINE_NOT_FOUND","有效线体不存在",HttpStatus.BAD_REQUEST);
        UUID id=UUID.randomUUID();
        try { jdbc.sql("INSERT INTO station(id,line_id,code,name,status) VALUES (:id,:lineId,:code,:name,'ACTIVE')")
                .param("id",id).param("lineId",lineId).param("code",code.trim()).param("name",name.trim()).update(); }
        catch(DataIntegrityViolationException ex){throw new ApiException("STATION_CODE_EXISTS","线体内站位编号已存在",HttpStatus.CONFLICT);}
        StationView result=station(id); audit.record(actor.userId(),"STATION_CREATE","STATION",id,"{}",requestId); return result;
    }

    @Transactional
    public LineView updateLine(CurrentUser actor, UUID id, String code, String name, String status,
                               long version, String requestId) {
        guard.requireSystemAdmin(actor); validate(code,name);
        String normalized=normalizeStatus(status);
        try {
            int updated=jdbc.sql("""
                    UPDATE production_line SET code=:code,name=:name,status=:status,
                      version=version+1,updated_at=CURRENT_TIMESTAMP
                    WHERE id=:id AND version=:version AND is_review_data=FALSE
                    """).param("code",code.trim()).param("name",name.trim()).param("status",normalized)
                    .param("id",id).param("version",version).update();
            if(updated!=1) conflictOrNotFound("production_line",id);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("LINE_CODE_EXISTS","班组内线体编号已存在",HttpStatus.CONFLICT);
        }
        LineView result=line(id); audit.record(actor.userId(),"LINE_UPDATE","PRODUCTION_LINE",id,"{}",requestId); return result;
    }

    @Transactional
    public StationView updateStation(CurrentUser actor, UUID id, String code, String name, String status,
                                     long version, String requestId) {
        guard.requireSystemAdmin(actor); validate(code,name);
        String normalized=normalizeStatus(status);
        try {
            int updated=jdbc.sql("""
                    UPDATE station SET code=:code,name=:name,status=:status,
                      version=version+1,updated_at=CURRENT_TIMESTAMP
                    WHERE id=:id AND version=:version
                      AND EXISTS (SELECT 1 FROM production_line l WHERE l.id=station.line_id AND l.is_review_data=FALSE)
                    """).param("code",code.trim()).param("name",name.trim()).param("status",normalized)
                    .param("id",id).param("version",version).update();
            if(updated!=1) conflictOrNotFound("station",id);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("STATION_CODE_EXISTS","线体内站位编号已存在",HttpStatus.CONFLICT);
        }
        StationView result=station(id); audit.record(actor.userId(),"STATION_UPDATE","STATION",id,"{}",requestId); return result;
    }

    @Transactional
    public void setLineStatus(CurrentUser actor, UUID id, String status, long version, String requestId) {
        guard.requireSystemAdmin(actor); String normalized=status==null?"":status.toUpperCase();
        if(!Set.of("ACTIVE","DISABLED").contains(normalized)) invalid();
        int updated=jdbc.sql("UPDATE production_line SET status=:status,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version")
                .param("status",normalized).param("id",id).param("version",version).update();
        if(updated!=1) conflict(); audit.record(actor.userId(),"LINE_STATUS","PRODUCTION_LINE",id,"{}",requestId);
    }

    @Transactional
    public void setStationStatus(CurrentUser actor, UUID id, String status, long version, String requestId) {
        guard.requireSystemAdmin(actor); String normalized=status==null?"":status.toUpperCase();
        if(!Set.of("ACTIVE","DISABLED").contains(normalized)) invalid();
        int updated=jdbc.sql("UPDATE station SET status=:status,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version")
                .param("status",normalized).param("id",id).param("version",version).update();
        if(updated!=1) conflict(); audit.record(actor.userId(),"STATION_STATUS","STATION",id,"{}",requestId);
    }

    @Transactional
    public ShiftView createShiftVersion(CurrentUser actor, String code, String name, LocalTime startsAt,
                                        LocalTime endsAt, LocalDate effectiveFrom, String requestId) {
        guard.requireSystemAdmin(actor); validate(code,name);
        if(startsAt==null||endsAt==null||effectiveFrom==null) invalid();
        String normalized=code.toUpperCase();
        jdbc.sql("""
                UPDATE shift_definition SET effective_to=:previousDay
                WHERE code=:code AND effective_to IS NULL AND effective_from<:effectiveFrom
                """).param("previousDay",effectiveFrom.minusDays(1)).param("code",normalized).param("effectiveFrom",effectiveFrom).update();
        UUID id=UUID.randomUUID();
        try { jdbc.sql("""
                INSERT INTO shift_definition(id,code,name,starts_at,ends_at,effective_from,status,created_by)
                VALUES (:id,:code,:name,:startsAt,:endsAt,:effectiveFrom,'ACTIVE',:actorId)
                """).param("id",id).param("code",normalized).param("name",name.trim()).param("startsAt",startsAt)
                .param("endsAt",endsAt).param("effectiveFrom",effectiveFrom).param("actorId",actor.userId()).update(); }
        catch(DataIntegrityViolationException ex){throw new ApiException("SHIFT_VERSION_EXISTS","该班次生效日期已经存在版本",HttpStatus.CONFLICT);}
        ShiftView result=shift(id); audit.record(actor.userId(),"SHIFT_VERSION_CREATE","SHIFT_DEFINITION",id,"{}",requestId); return result;
    }

    @Transactional
    public void saveCalendarDay(CurrentUser actor, LocalDate date, String dayType, String name, String requestId) {
        guard.requireSystemAdmin(actor); String normalized=dayType==null?"":dayType.toUpperCase();
        if(date==null||!Set.of("HOLIDAY","WORKDAY").contains(normalized)||name==null||name.isBlank()) invalid();
        jdbc.sql("""
                INSERT INTO work_calendar(calendar_date,day_type,name,source,updated_by) VALUES (:date,:type,:name,'ADMIN',:actorId)
                ON CONFLICT (calendar_date) DO UPDATE SET day_type=EXCLUDED.day_type,name=EXCLUDED.name,
                  source='ADMIN',updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP
                """).param("date",date).param("type",normalized).param("name",name.trim()).param("actorId",actor.userId()).update();
        audit.record(actor.userId(),"CALENDAR_UPDATE","WORK_CALENDAR",null,"{}",requestId);
    }

    private CatalogView catalog(String status, boolean review) {
        String reviewValue=review?"TRUE":"FALSE";
        String lineWhere=" WHERE l.is_review_data="+reviewValue+(status==null?"":" AND l.status='ACTIVE' ");
        String stationWhere=" WHERE l.is_review_data="+reviewValue+(status==null?"":" AND s.status='ACTIVE' ");
        String shiftWhere=status==null?"":" WHERE status='ACTIVE' ";
        List<LineView> lines=jdbc.sql("SELECT l.id,l.org_unit_id,o.name team_name,l.code,l.name,l.status,l.version FROM production_line l JOIN org_unit o ON o.id=l.org_unit_id"+lineWhere+" ORDER BY o.name,l.code")
                .query((rs,n)->new LineView(rs.getObject("id",UUID.class),rs.getObject("org_unit_id",UUID.class),rs.getString("team_name"),rs.getString("code"),rs.getString("name"),rs.getString("status"),rs.getLong("version"))).list();
        List<StationView> stations=jdbc.sql("SELECT s.id,s.line_id,l.name line_name,s.code,s.name,s.status,s.version FROM station s JOIN production_line l ON l.id=s.line_id"+stationWhere+" ORDER BY l.name,s.code")
                .query((rs,n)->new StationView(rs.getObject("id",UUID.class),rs.getObject("line_id",UUID.class),rs.getString("line_name"),rs.getString("code"),rs.getString("name"),rs.getString("status"),rs.getLong("version"))).list();
        List<ShiftView> shifts=jdbc.sql("SELECT id,code,name,starts_at,ends_at,effective_from,effective_to,status FROM shift_definition"+shiftWhere+" ORDER BY code,effective_from DESC")
                .query((rs,n)->new ShiftView(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getObject("starts_at",LocalTime.class),rs.getObject("ends_at",LocalTime.class),rs.getObject("effective_from",LocalDate.class),rs.getObject("effective_to",LocalDate.class),rs.getString("status"))).list();
        return new CatalogView(lines,stations,shifts);
    }

    private boolean isReview(CurrentUser actor){return "wxreview".equalsIgnoreCase(actor.employeeNo());}

    private LineView line(UUID id){return jdbc.sql("SELECT l.id,l.org_unit_id,o.name team_name,l.code,l.name,l.status,l.version FROM production_line l JOIN org_unit o ON o.id=l.org_unit_id WHERE l.id=:id").param("id",id).query((rs,n)->new LineView(rs.getObject("id",UUID.class),rs.getObject("org_unit_id",UUID.class),rs.getString("team_name"),rs.getString("code"),rs.getString("name"),rs.getString("status"),rs.getLong("version"))).single();}
    private StationView station(UUID id){return jdbc.sql("SELECT s.id,s.line_id,l.name line_name,s.code,s.name,s.status,s.version FROM station s JOIN production_line l ON l.id=s.line_id WHERE s.id=:id").param("id",id).query((rs,n)->new StationView(rs.getObject("id",UUID.class),rs.getObject("line_id",UUID.class),rs.getString("line_name"),rs.getString("code"),rs.getString("name"),rs.getString("status"),rs.getLong("version"))).single();}
    private ShiftView shift(UUID id){return jdbc.sql("SELECT id,code,name,starts_at,ends_at,effective_from,effective_to,status FROM shift_definition WHERE id=:id").param("id",id).query((rs,n)->new ShiftView(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getObject("starts_at",LocalTime.class),rs.getObject("ends_at",LocalTime.class),rs.getObject("effective_from",LocalDate.class),rs.getObject("effective_to",LocalDate.class),rs.getString("status"))).single();}
    private void validate(String code,String name){if(code==null||!CODE.matcher(code).matches()||name==null||name.isBlank())invalid();}
    private String normalizeStatus(String status){String normalized=status==null?"":status.toUpperCase();if(!Set.of("ACTIVE","DISABLED").contains(normalized))invalid();return normalized;}
    private void conflictOrNotFound(String table,UUID id){Long exists=jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE id=:id").param("id",id).query(Long.class).single();if(exists==0)throw new ApiException("CATALOG_ITEM_NOT_FOUND","基础资料不存在",HttpStatus.NOT_FOUND);conflict();}
    private void invalid(){throw new ApiException("INVALID_CATALOG_ITEM","基础资料内容不合法",HttpStatus.BAD_REQUEST);}
    private void conflict(){throw new ApiException("VERSION_CONFLICT","资料已被其他人更新，请刷新后重试",HttpStatus.CONFLICT);}

    public record CatalogView(List<LineView> lines,List<StationView> stations,List<ShiftView> shifts){}
    public record LineView(UUID id,UUID teamId,String teamName,String code,String name,String status,long version){}
    public record StationView(UUID id,UUID lineId,String lineName,String code,String name,String status,long version){}
    public record ShiftView(UUID id,String code,String name,LocalTime startsAt,LocalTime endsAt,LocalDate effectiveFrom,LocalDate effectiveTo,String status){}
}
