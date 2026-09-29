package com.acme.performance.performance.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.organization.model.BusinessRoles;
import com.acme.performance.settlement.service.GradeDistribution;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PerformanceAnalyticsService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc; private final GradeDistribution distribution=new GradeDistribution();
    public PerformanceAnalyticsService(JdbcClient jdbc){this.jdbc=jdbc;}

    @Transactional(readOnly=true)
    public PersonalView mine(CurrentUser actor,String period){
        requireScored(actor);String normalized=period(period);List<PersonView> all=calculate(normalized,true,"wxreview".equalsIgnoreCase(actor.employeeNo()));
        PersonView self=all.stream().filter(item->item.userId().equals(actor.userId())).findFirst().orElse(empty(actor,normalized));
        String finalGrade=jdbc.sql("SELECT grade FROM grade_snapshot WHERE user_id=:userId AND period=:period AND status='PUBLISHED' ORDER BY published_at DESC NULLS LAST,created_at DESC LIMIT 1")
                .param("userId",actor.userId()).param("period",normalized).query(String.class).optional().orElse(null);
        List<DailyPoint> daily=jdbc.sql("""
                SELECT biz_date,COALESCE(SUM(actual_score),0) score FROM score_event
                WHERE user_id=:userId AND biz_date>=:from AND biz_date<:to GROUP BY biz_date ORDER BY biz_date DESC
                """).param("userId",actor.userId()).param("from",YearMonth.parse(normalized).atDay(1)).param("to",YearMonth.parse(normalized).plusMonths(1).atDay(1))
                .query((rs,n)->new DailyPoint(rs.getObject("biz_date",LocalDate.class),rs.getBigDecimal("score"))).list();
        return new PersonalView(normalized,self.total(),self.averageScore(),self.base(),self.bonus(),self.penalty(),self.shiftCount(),self.rank(),finalGrade,daily);
    }

    @Transactional(readOnly=true)
    public Overview overview(CurrentUser actor,String period){
        if(!actor.roles().contains(BusinessRoles.SUPERVISOR)&&!actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER))forbidden();
        String normalized=period(period);List<PersonView> items=calculate(normalized,true,false);Map<String,Long> grades=new LinkedHashMap<>();
        for(PersonView item:items){if(item.previewGrade()!=null)grades.merge(item.previewGrade(),1L,Long::sum);}
        return new Overview(normalized,(int)items.stream().filter(item->item.shiftCount()>0).count(),items,grades);
    }

    private List<PersonView> calculate(String period,boolean includePreview,boolean reviewAccount){
        YearMonth month=YearMonth.parse(period);LocalDate from=month.atDay(1),to=month.plusMonths(1).atDay(1);
        List<Raw> rows=jdbc.sql("""
                SELECT u.id,u.employee_no,u.display_name,u.org_unit_id,o.name team_name,
                  COALESCE(s.base,0) base,COALESCE(s.bonus,0) bonus,COALESCE(s.penalty,0) penalty,COALESCE(s.total,0) total,
                  (SELECT COUNT(*) FROM shift_score sh WHERE sh.user_id=u.id AND sh.status='POSTED'
                    AND sh.business_date>=:from AND sh.business_date<:to) shift_count,
                  EXISTS(SELECT 1 FROM d_grade_nomination d WHERE d.user_id=u.id AND d.period=:period AND d.status='CONFIRMED') d_grade
                FROM app_user u JOIN org_unit o ON o.id=u.org_unit_id LEFT JOIN score_summary s ON s.user_id=u.id AND s.period=:period
                WHERE u.status='ACTIVE' AND u.is_review_account=:reviewAccount AND EXISTS(SELECT 1 FROM role_binding rb WHERE rb.user_id=u.id
                  AND rb.role_code IN ('TECHNICIAN','ASSISTANT_ENGINEER'))
                ORDER BY u.employee_no
                """).param("from",from).param("to",to).param("period",period).param("reviewAccount",reviewAccount).query((rs,n)->new Raw(rs.getObject("id",UUID.class),
                        rs.getString("employee_no"),rs.getString("display_name"),rs.getObject("org_unit_id",UUID.class),
                        rs.getString("team_name"),rs.getBigDecimal("base"),rs.getBigDecimal("bonus"),rs.getBigDecimal("penalty"),
                        rs.getBigDecimal("total"),rs.getInt("shift_count"),rs.getBoolean("d_grade"))).list();
        List<Raw> eligible=rows.stream().filter(r->r.shiftCount()>0).toList();
        Comparator<Raw> order=Comparator.comparing(this::average).reversed().thenComparing(Raw::penalty)
                .thenComparing(this::baseRate,Comparator.reverseOrder()).thenComparing(Raw::bonus,Comparator.reverseOrder())
                .thenComparing(Raw::employeeNo);
        List<Raw> normal=eligible.stream().filter(r->!r.dGrade()).sorted(order).toList();List<Raw> d=eligible.stream().filter(Raw::dGrade).sorted(order).toList();
        List<GradeDistribution.ScoreInput> normalInput=normal.stream().map(r->new GradeDistribution.ScoreInput(r.id(),average(r),baseRate(r),r.bonus(),r.penalty())).toList();
        List<GradeDistribution.ScoreInput> dInput=d.stream().map(r->new GradeDistribution.ScoreInput(r.id(),average(r),baseRate(r),r.bonus(),r.penalty())).toList();
        Map<UUID,GradeDistribution.Result> gradeByUser=new HashMap<>();distribution.distribute(normalInput,dInput).forEach(result->gradeByUser.put(result.userId(),result));
        List<PersonView> result=new ArrayList<>();for(Raw row:rows){GradeDistribution.Result grade=gradeByUser.get(row.id());result.add(new PersonView(row.id(),row.employeeNo(),row.name(),row.teamId(),row.teamName(),row.base(),row.bonus(),row.penalty(),row.total(),row.shiftCount(),average(row),grade==null?0:grade.rank(),includePreview&&grade!=null?grade.proposedGrade():null,grade!=null&&grade.manualRequired(),row.dGrade()));}
        result.sort(Comparator.comparingInt((PersonView p)->p.rank()==0?Integer.MAX_VALUE:p.rank()));return result;
    }

    private BigDecimal average(Raw row){return row.shiftCount()==0?BigDecimal.ZERO:row.total().divide(BigDecimal.valueOf(row.shiftCount()),6,RoundingMode.HALF_UP);}
    private BigDecimal baseRate(Raw row){return row.shiftCount()==0?BigDecimal.ZERO:row.base().divide(BigDecimal.valueOf(row.shiftCount()*10L),6,RoundingMode.HALF_UP);}
    private String period(String value){String normalized=value==null||value.isBlank()?YearMonth.now(ZONE).toString():value;if(!normalized.matches("\\d{4}-(0[1-9]|1[0-2])"))throw new ApiException("INVALID_PERIOD","月份格式应为yyyy-MM",HttpStatus.BAD_REQUEST);return normalized;}
    private PersonView empty(CurrentUser actor,String period){return new PersonView(actor.userId(),actor.employeeNo(),actor.displayName(),actor.orgUnitId(),"",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,0,BigDecimal.ZERO,0,null,false,false);}
    private void requireScored(CurrentUser actor){if(actor.roles().stream().noneMatch(BusinessRoles.SCORED::contains))forbidden();}
    private void forbidden(){throw new ApiException("FORBIDDEN","当前角色无权查看该绩效数据",HttpStatus.FORBIDDEN);}
    private record Raw(UUID id,String employeeNo,String name,UUID teamId,String teamName,BigDecimal base,BigDecimal bonus,BigDecimal penalty,BigDecimal total,int shiftCount,boolean dGrade){}
    public record DailyPoint(LocalDate date,BigDecimal score){}
    public record PersonalView(String period,BigDecimal total,BigDecimal averageScore,BigDecimal base,BigDecimal bonus,BigDecimal penalty,int shiftCount,int rank,String finalGrade,List<DailyPoint> daily){}
    public record PersonView(UUID userId,String employeeNo,String name,UUID teamId,String teamName,BigDecimal base,BigDecimal bonus,BigDecimal penalty,BigDecimal total,int shiftCount,BigDecimal averageScore,int rank,String previewGrade,boolean boundaryTie,boolean dGrade){}
    public record Overview(String period,int eligibleCount,List<PersonView> people,Map<String,Long> gradeCounts){}
}
