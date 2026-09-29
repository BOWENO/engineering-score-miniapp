package com.acme.performance.export.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.export.service.WorkbookExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/exports")
public class WorkbookExportController {
    private static final MediaType XLSX=MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final WorkbookExportService service; public WorkbookExportController(WorkbookExportService service){this.service=service;}
    @GetMapping("/performance") ResponseEntity<byte[]> performance(CurrentUser actor,@RequestParam(required=false) String period,HttpServletRequest request){String p=period==null||period.isBlank()?YearMonth.now().toString():period;return file(service.performance(actor,p,requestId(request)),"绩效统计_"+p+".xlsx");}
    @GetMapping("/schedules") ResponseEntity<byte[]> schedules(CurrentUser actor,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,HttpServletRequest request){return file(service.schedules(actor,from,to,requestId(request)),"责任排班_"+from+"_"+to+".xlsx");}
    @GetMapping("/incidents") ResponseEntity<byte[]> incidents(CurrentUser actor,@RequestParam(required=false) List<UUID> equipmentIds,@RequestParam(required=false) UUID lineId,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="ARCHIVED") String status,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,HttpServletRequest request){return file(service.incidents(actor,equipmentIds,lineId,from,to,keyword,status,requestId(request)),"设备异常档案.xlsx");}
    @GetMapping("/incidents/monthly") ResponseEntity<byte[]> incidentMonthly(CurrentUser actor,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,HttpServletRequest request){return file(service.incidentMonthly(actor,from,to,requestId(request)),"异常月度汇总.xlsx");}
    @GetMapping("/equipment") ResponseEntity<byte[]> equipment(CurrentUser actor,HttpServletRequest request){return file(service.equipment(actor,requestId(request)),"设备台账.xlsx");}
    private ResponseEntity<byte[]> file(byte[] body,String name){String encoded=URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20");return ResponseEntity.ok().contentType(XLSX).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+encoded).cacheControl(CacheControl.noStore()).body(body);}
    private String requestId(HttpServletRequest request){return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));}
}
