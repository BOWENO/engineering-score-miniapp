package com.acme.performance.publicity.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Department publicity: a separate, read-only projection without private evidence or contact data. */
@Service
public class DailyDeductionService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;
    public DailyDeductionService(JdbcClient jdbc){this.jdbc=jdbc;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Board board(CurrentUser viewer,LocalDate date,int page,int size){
        LocalDate today=LocalDate.now(ZONE),day=date==null?today:date;
        if(page<0||page>100000||size<1||size>50||day.isAfter(today))throw new ApiException("INVALID_PUBLICITY_QUERY","请选择有效日期和分页范围",HttpStatus.BAD_REQUEST);
        if(!viewer.administrator()&&Collections.disjoint(viewer.roles(),Set.of("TECHNICIAN","ASSISTANT_ENGINEER","SUPERVISOR","DEPARTMENT_MANAGER")))
            throw new ApiException("FORBIDDEN","当前身份无权查看扣分公示",HttpStatus.FORBIDDEN);
        var actor=jdbc.sql("SELECT u.org_unit_id,(u.is_review_account OR o.is_review_data) is_review_account FROM app_user u JOIN org_unit o ON o.id=u.org_unit_id WHERE u.id=:id AND u.status='ACTIVE'")
                .param("id",viewer.userId()).query((rs,n)->new Actor(rs.getObject("org_unit_id",UUID.class),rs.getBoolean("is_review_account"))).optional()
                .orElseThrow(()->new ApiException("FORBIDDEN","当前账号不可用",HttpStatus.FORBIDDEN));
        if(actor.review())return new Board(day,today,"审核演示",0,0,BigDecimal.ZERO,page,size,false,List.of());
        var org=jdbc.sql("""
                WITH RECURSIVE parents AS (
                  SELECT id,parent_id,name,type,is_review_data,ARRAY[id] path FROM org_unit WHERE id=:org
                  UNION ALL SELECT o.id,o.parent_id,o.name,o.type,o.is_review_data,p.path||o.id
                  FROM org_unit o JOIN parents p ON p.parent_id=o.id WHERE NOT o.id=ANY(p.path)
                ) SELECT id,name FROM parents WHERE type='DEPARTMENT' AND NOT is_review_data ORDER BY cardinality(path) LIMIT 1
                """).param("org",actor.org()).query((rs,n)->new Org(rs.getObject("id",UUID.class),rs.getString("name"))).optional();
        if(org.isEmpty())return new Board(day,today,"当前组织",0,0,BigDecimal.ZERO,page,size,false,List.of());
        var from=day.atStartOfDay(ZONE).toOffsetDateTime();var to=day.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        String base="""
                WITH RECURSIVE tree AS (
                  SELECT id FROM org_unit WHERE id=:org AND NOT is_review_data
                  UNION SELECT o.id FROM org_unit o JOIN tree t ON o.parent_id=t.id WHERE NOT o.is_review_data
                ), entries AS (
                  SELECT e.id,e.created_at,p.occurred_at,p.description,p.case_type,u.id user_id,u.display_name,o.name team_name,
                    ABS(e.actual_score) score,
                    CASE WHEN EXISTS(SELECT 1 FROM role_binding rb WHERE rb.user_id=u.id AND rb.role_code='ASSISTANT_ENGINEER')
                      THEN '助理工程师' ELSE '技术员' END role_name,
                    EXISTS(SELECT 1 FROM performance_appeal a WHERE a.case_id=p.id AND a.status='PENDING') under_appeal
                  FROM score_event e JOIN performance_case p ON p.id=e.source_id AND e.source='PERFORMANCE_CASE'
                  JOIN app_user u ON u.id=p.target_user_id JOIN org_unit o ON o.id=u.org_unit_id
                  WHERE u.org_unit_id IN (SELECT id FROM tree) AND NOT u.is_review_account
                    AND e.user_id=u.id AND e.created_at>=:from AND e.created_at<:to AND e.actual_score<0
                    AND p.status='EFFECTIVE' AND p.case_type IN ('BASE_DEDUCTION','SPECIAL_DEDUCTION')
                    AND EXISTS(SELECT 1 FROM role_binding rb WHERE rb.user_id=u.id AND rb.role_code IN ('TECHNICIAN','ASSISTANT_ENGINEER'))
                )
                """;
        var stats=jdbc.sql(base+"SELECT COUNT(*) items,COUNT(DISTINCT user_id) people,COALESCE(SUM(score),0) score FROM entries")
                .param("org",org.get().id()).param("from",from).param("to",to)
                .query((rs,n)->new Stats(rs.getLong("items"),rs.getLong("people"),rs.getBigDecimal("score"))).single();
        var rows=jdbc.sql(base+"SELECT * FROM entries ORDER BY created_at DESC,id DESC LIMIT :size OFFSET :offset")
                .param("org",org.get().id()).param("from",from).param("to",to).param("size",size).param("offset",(long)page*size)
                .query((rs,n)->new Item(rs.getObject("id",UUID.class),rs.getString("display_name"),rs.getString("team_name"),rs.getString("role_name"),
                        rs.getString("description"),rs.getBigDecimal("score"),"BASE_DEDUCTION".equals(rs.getString("case_type"))?"基础扣分":"特殊扣分",
                        rs.getObject("created_at",OffsetDateTime.class).atZoneSameInstant(ZONE).format(DateTimeFormatter.ofPattern("HH:mm")),
                        rs.getObject("occurred_at",OffsetDateTime.class).atZoneSameInstant(ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),rs.getBoolean("under_appeal"))).list();
        return new Board(day,today,org.get().name(),stats.items(),stats.people(),stats.score(),page,size,((long)page+1)*size<stats.items(),rows);
    }
    private record Actor(UUID org,boolean review){}
    private record Org(UUID id,String name){}
    private record Stats(long items,long people,BigDecimal score){}
    public record Board(LocalDate date,LocalDate today,String scopeName,long total,long people,BigDecimal totalScore,int page,int size,boolean hasMore,List<Item> items){}
    public record Item(UUID id,String displayName,String teamName,String roleName,String description,BigDecimal score,String category,String effectiveTime,String occurredTime,boolean underAppeal){}
}
