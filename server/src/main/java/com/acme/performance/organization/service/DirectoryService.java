package com.acme.performance.organization.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.organization.model.BusinessRoles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class DirectoryService {
    private final JdbcClient jdbc;public DirectoryService(JdbcClient jdbc){this.jdbc=jdbc;}
    @Transactional(readOnly=true) public List<Person> people(CurrentUser actor){
        String where;Map<String,Object> params=new HashMap<>();
        if("wxreview".equalsIgnoreCase(actor.employeeNo())){where=" WHERE u.id=:userId AND u.is_review_account=TRUE ";params.put("userId",actor.userId());}
        else if(actor.administrator()||actor.roles().contains(BusinessRoles.SUPERVISOR)||actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER))where=" WHERE u.status='ACTIVE' AND u.is_review_account=FALSE ";
        else if(actor.roles().contains(BusinessRoles.ASSISTANT_ENGINEER)){where=" WHERE u.status='ACTIVE' AND u.is_review_account=FALSE AND u.org_unit_id=:teamId ";params.put("teamId",actor.orgUnitId());}
        else {where=" WHERE u.id=:userId AND u.is_review_account=FALSE ";params.put("userId",actor.userId());}
        var spec=jdbc.sql("""
                SELECT u.id,u.employee_no,u.display_name,u.is_administrator,u.org_unit_id,o.name team_name,
                  COALESCE(string_agg(DISTINCT rb.role_code,',' ORDER BY rb.role_code),'') roles
                FROM app_user u JOIN org_unit o ON o.id=u.org_unit_id LEFT JOIN role_binding rb ON rb.user_id=u.id
                """+where+" GROUP BY u.id,u.employee_no,u.display_name,u.org_unit_id,o.name ORDER BY u.employee_no");
        for(var entry:params.entrySet())spec=spec.param(entry.getKey(),entry.getValue());
        return spec.query((rs,n)->{
            UUID userId=rs.getObject("id",UUID.class);
            boolean maskAdministrator=rs.getBoolean("is_administrator")&&!userId.equals(actor.userId());
            return new Person(userId,maskAdministrator?"—":rs.getString("employee_no"),
                    maskAdministrator?"系统管理员":rs.getString("display_name"),
                    rs.getObject("org_unit_id",UUID.class),rs.getString("team_name"),
                    rs.getString("roles").isBlank()?List.of():List.of(rs.getString("roles").split(",")));
        }).list();
    }
    public record Person(UUID id,String employeeNo,String name,UUID teamId,String teamName,List<String> roles){}
}
