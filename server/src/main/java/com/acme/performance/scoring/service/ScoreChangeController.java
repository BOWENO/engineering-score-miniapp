package com.acme.performance.scoring.service;

import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticated invalidation channel. Business endpoints retain their own access checks. */
@RestController
@RequestMapping("/api/score-changes")
public class ScoreChangeController {
    private final JdbcClient jdbc;
    private final Set<Pending> pending=ConcurrentHashMap.newKeySet();
    public ScoreChangeController(JdbcClient jdbc){this.jdbc=jdbc;}
    String revision(){return jdbc.sql("SELECT revision::text FROM score_live_revision WHERE id=1").query(String.class).single()
        +":"+LocalDate.now(ZoneId.of("Asia/Shanghai"));}
    @GetMapping
    public DeferredResult<ApiResponse<Change>> watch(@RequestParam(defaultValue="") String since,HttpServletRequest request){
        String requestId=String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
        var result=new DeferredResult<ApiResponse<Change>>(20000L);
        String current=revision();
        if(!current.equals(since)){result.setResult(ApiResponse.success(new Change(current),requestId));return result;}
        var item=new Pending(since,requestId,result);
        pending.add(item);
        result.onCompletion(()->pending.remove(item));
        result.onTimeout(()->{pending.remove(item);result.setResult(ApiResponse.success(new Change(since),requestId));});
        result.onError(error->pending.remove(item));
        return result;
    }
    @Scheduled(fixedDelay=500)
    public void deliver(){
        if(pending.isEmpty())return;
        String current=revision();
        for(var item:pending)if(!current.equals(item.since())){
            pending.remove(item);item.result().setResult(ApiResponse.success(new Change(current),item.requestId()));
        }
    }
    public record Change(String revision){}
    private record Pending(String since,String requestId,DeferredResult<ApiResponse<Change>> result){}
}
