package com.acme.performance.export.service;

import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.equipment.service.EquipmentService;
import com.acme.performance.incident.service.IncidentService;
import com.acme.performance.incident.service.IncidentReportService;
import com.acme.performance.organization.model.BusinessRoles;
import com.acme.performance.performance.service.PerformanceAnalyticsService;
import com.acme.performance.schedule.service.ScheduleService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
public class WorkbookExportService {
    private final PerformanceAnalyticsService analytics;
    private final ScheduleService schedules;
    private final IncidentService incidents;
    private final EquipmentService equipment;
    private final IncidentReportService incidentReports;
    private final AuditLogService audit;

    public WorkbookExportService(PerformanceAnalyticsService analytics, ScheduleService schedules,
                                 IncidentService incidents, EquipmentService equipment, AuditLogService audit,
                                 IncidentReportService incidentReports) {
        this.analytics=analytics; this.schedules=schedules; this.incidents=incidents; this.equipment=equipment; this.audit=audit; this.incidentReports=incidentReports;
    }

    public byte[] performance(CurrentUser actor,String period,String requestId) {
        requireManagement(actor); var overview=analytics.overview(actor,period);
        try(var workbook=new XSSFWorkbook()) {
            Sheet sheet=sheet(workbook,"月度绩效"); String[] headers={"排名","工号","姓名","班组","有效班次","基础分","加分","扣分","总分","平均分","预估等级","D级"};
            header(sheet,headers); int row=1;
            for(var item:overview.people()) cells(sheet.createRow(row++),item.rank(),item.employeeNo(),item.name(),item.teamName(),item.shiftCount(),item.base(),item.bonus(),item.penalty(),item.total(),item.averageScore(),item.previewGrade(),item.dGrade()?"是":"否");
            finish(sheet,headers.length); audit.record(actor.userId(),"EXPORT_PERFORMANCE","PERFORMANCE",null,"{\"period\":\""+overview.period()+"\"}",requestId); return bytes(workbook);
        } catch(Exception ex) { throw exportError(ex); }
    }

    public byte[] schedules(CurrentUser actor,LocalDate from,LocalDate to,String requestId) {
        var items=schedules.list(actor,from,to,null,null);
        try(var workbook=new XSSFWorkbook()) {
            Sheet sheet=sheet(workbook,"责任排班"); String[] headers={"业务日期","班次","工号","姓名","班组","线体","站位","开始时间","结束时间","状态","系统确认时间（非阅读回执）"};
            header(sheet,headers); int row=1;
            for(var item:items) cells(sheet.createRow(row++),item.businessDate(),"NIGHT".equals(item.shiftCode())?"夜班":"白班",item.employeeNo(),item.displayName(),item.teamName(),item.lineName(),item.stationName(),item.shiftStartsAt(),item.shiftEndsAt(),item.status(),item.acknowledgedAt());
            finish(sheet,headers.length); audit.record(actor.userId(),"EXPORT_SCHEDULE","SCHEDULE_ASSIGNMENT",null,"{}",requestId); return bytes(workbook);
        } catch(Exception ex) { throw exportError(ex); }
    }

    public byte[] incidents(CurrentUser actor,List<UUID> equipmentIds,LocalDate from,LocalDate to,String requestId) {
        return incidents(actor,equipmentIds,null,from,to,null,"ARCHIVED",requestId);
    }
    public byte[] incidents(CurrentUser actor,List<UUID> equipmentIds,UUID lineId,LocalDate from,LocalDate to,String keyword,String status,String requestId) {
        var items=incidents.exportRecords(actor,equipmentIds,lineId,from,to,keyword,status);
        LinkedHashMap<UUID,IncidentService.DeviceItem> deviceMap=new LinkedHashMap<>();
        items.forEach(item->item.devices().forEach(device->deviceMap.putIfAbsent(device.id(),device)));
        try(var workbook=new XSSFWorkbook()) {
            Set<String> names=new HashSet<>();
            if(deviceMap.isEmpty()) { Sheet empty=sheet(workbook,"异常档案"); header(empty,new String[]{"无符合条件的已归档异常"}); }
            for(var device:deviceMap.values()) {
                String name=uniqueSheetName(device.code()+"_"+device.name(),names); Sheet sheet=sheet(workbook,name);
                String[] headers={"异常单号","发生日期","发生时间","班组","线体","站位","设备编号","设备名称","设备类别","责任人工号","责任人","异常现象","处理方法","问题根因","长期对策","说明状态","异常状态","追加说明"};
                header(sheet,headers); int row=1;
                for(var incident:items) {
                    if(incident.devices().stream().noneMatch(it->it.id().equals(device.id()))) continue;
                    String notes=incident.notes().stream().map(note->note.actorName()+"："+note.content()).reduce((a,b)->a+"\n"+b).orElse("");
                    for(var statement:incident.statements()) cells(sheet.createRow(row++),incident.incidentNo(),incident.businessDate(),incident.occurredAt(),incident.teamName(),incident.lineName(),incident.stationName(),device.code(),device.name(),device.category(),statement.employeeNo(),statement.responsibleName(),statement.phenomenon(),statement.handlingMethod(),statement.rootCause(),statement.longTermAction(),statement.status(),incident.status(),notes);
                }
                finish(sheet,headers.length);
            }
            audit.record(actor.userId(),"EXPORT_INCIDENT_ARCHIVE","EQUIPMENT_INCIDENT",null,"{\"deviceCount\":"+deviceMap.size()+"}",requestId); return bytes(workbook);
        } catch(Exception ex) { throw exportError(ex); }
    }

    public byte[] equipment(CurrentUser actor,String requestId) {
        var items=equipment.all(actor);
        try(var workbook=new XSSFWorkbook()) {
            Sheet sheet=sheet(workbook,"设备台账"); String[] headers={"设备编号","设备名称","设备类别","班组","线体","站位","状态"}; header(sheet,headers); int row=1;
            for(var item:items) cells(sheet.createRow(row++),item.code(),item.name(),item.category(),item.orgName(),item.lineName(),item.stationName(),"ACTIVE".equals(item.status())?"启用":"停用");
            finish(sheet,headers.length); audit.record(actor.userId(),"EXPORT_EQUIPMENT","EQUIPMENT",null,"{}",requestId); return bytes(workbook);
        } catch(Exception ex) { throw exportError(ex); }
    }

    public byte[] incidentMonthly(CurrentUser actor,LocalDate from,LocalDate to,String requestId) {
        var report=incidentReports.monthly(actor,from,to);
        try(var workbook=new XSSFWorkbook()) {
            Sheet summary=sheet(workbook,"月度汇总");header(summary,new String[]{"指标","数值"});
            cells(summary.createRow(1),"异常总数",report.total());cells(summary.createRow(2),"已归档",report.archived());
            cells(summary.createRow(3),"处理中",report.open());cells(summary.createRow(4),"累计停机分钟",report.downtimeMinutes());
            cells(summary.createRow(5),"平均关闭小时",report.averageCloseHours());cells(summary.createRow(6),"整改超期",report.overdueActions());finish(summary,2);
            Sheet details=sheet(workbook,"异常明细");String[] headers={"档案编号","班组","分类","严重程度","状态","停机分钟","发生时间","归档时间","根因分类"};header(details,headers);int row=1;
            for(var item:report.incidents())cells(details.createRow(row++),item.incidentNo(),item.teamName(),item.categoryCode(),item.severity(),item.status(),item.downtimeMinutes(),item.occurredAt(),item.archivedAt(),item.rootCauseCategory());finish(details,headers.length);
            Sheet causes=sheet(workbook,"根因分布");header(causes,new String[]{"根因分类","数量"});row=1;for(var item:report.rootCauses())cells(causes.createRow(row++),item.name(),item.count());finish(causes,2);
            audit.record(actor.userId(),"EXPORT_INCIDENT_MONTHLY","EQUIPMENT_INCIDENT",null,"{\"from\":\""+report.from()+"\",\"to\":\""+report.to()+"\"}",requestId);return bytes(workbook);
        } catch(Exception ex){throw exportError(ex);}
    }

    private Sheet sheet(Workbook workbook,String name) { Sheet sheet=workbook.createSheet(WorkbookUtil.createSafeSheetName(name));sheet.createFreezePane(0,1);return sheet; }
    private void header(Sheet sheet,String[] values) { Row row=sheet.createRow(0);CellStyle style=sheet.getWorkbook().createCellStyle();style.setFillForegroundColor(IndexedColors.TEAL.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);Font font=sheet.getWorkbook().createFont();font.setBold(true);font.setColor(IndexedColors.WHITE.getIndex());style.setFont(font);for(int i=0;i<values.length;i++){Cell cell=row.createCell(i);cell.setCellValue(values[i]);cell.setCellStyle(style);} }
    private void cells(Row row,Object...values) { for(int i=0;i<values.length;i++){Cell cell=row.createCell(i);Object value=values[i];if(value==null)cell.setCellValue("");else if(value instanceof BigDecimal n)cell.setCellValue(n.doubleValue());else if(value instanceof Number n)cell.setCellValue(n.doubleValue());else cell.setCellValue(String.valueOf(value));CellStyle style=row.getSheet().getWorkbook().createCellStyle();style.setVerticalAlignment(VerticalAlignment.TOP);style.setWrapText(true);cell.setCellStyle(style);} }
    private void finish(Sheet sheet,int columns) { sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,sheet.getLastRowNum()),0,Math.max(0,columns-1)));for(int i=0;i<columns;i++){sheet.autoSizeColumn(i);sheet.setColumnWidth(i,Math.min(Math.max(sheet.getColumnWidth(i)+512,2500),12000));} }
    private byte[] bytes(Workbook workbook) throws Exception { ByteArrayOutputStream output=new ByteArrayOutputStream();workbook.write(output);return output.toByteArray(); }
    private String uniqueSheetName(String raw,Set<String> names) { String base=WorkbookUtil.createSafeSheetName(raw);if(base.length()>28)base=base.substring(0,28);String name=base;int n=2;while(!names.add(name))name=base.substring(0,Math.min(base.length(),25))+"_"+n++;return name; }
    private void requireManagement(CurrentUser actor) { if(!actor.roles().contains(BusinessRoles.SUPERVISOR)&&!actor.roles().contains(BusinessRoles.DEPARTMENT_MANAGER))throw new ApiException("FORBIDDEN","仅主管或部长可导出全员绩效",HttpStatus.FORBIDDEN); }
    private ApiException exportError(Exception ex) { return new ApiException("EXPORT_FAILED","Excel导出失败，请稍后重试",HttpStatus.INTERNAL_SERVER_ERROR); }
}
