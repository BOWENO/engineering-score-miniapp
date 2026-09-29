package com.acme.performance.equipment.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeviceExceptionExportService {
    private static final String[] HEADERS = {
            "序号", "发生日期", "设备类别", "设备编号", "设备名称", "异常现象", "处理方法",
            "问题根因", "长期对策", "填报人", "审核人", "审核时间"
    };

    public byte[] export(List<DeviceExceptionService.ExceptionView> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle bodyStyle = bodyStyle(workbook);
            Map<UUID, List<DeviceExceptionService.ExceptionView>> grouped = rows.stream().collect(
                    java.util.stream.Collectors.groupingBy(DeviceExceptionService.ExceptionView::equipmentId,
                            LinkedHashMap::new, java.util.stream.Collectors.toList()));
            if (grouped.isEmpty()) {
                Sheet sheet = workbook.createSheet("无归档记录");
                Row header = sheet.createRow(0);
                for (int index = 0; index < HEADERS.length; index++) {
                    Cell cell = header.createCell(index); cell.setCellValue(HEADERS[index]); cell.setCellStyle(headerStyle);
                }
            }
            for (List<DeviceExceptionService.ExceptionView> deviceRows : grouped.values()) {
                var first = deviceRows.getFirst();
                String baseName = WorkbookUtil.createSafeSheetName(first.equipmentCode() + "-" + first.equipmentName());
                Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, baseName));
                sheet.createFreezePane(0, 1);
                sheet.setAutobreaks(true);
                Row header = sheet.createRow(0);
                for (int index = 0; index < HEADERS.length; index++) {
                    Cell cell = header.createCell(index); cell.setCellValue(HEADERS[index]); cell.setCellStyle(headerStyle);
                }
                for (int rowIndex = 0; rowIndex < deviceRows.size(); rowIndex++) {
                    var item = deviceRows.get(rowIndex);
                    Row row = sheet.createRow(rowIndex + 1);
                    String[] values = {
                            String.valueOf(rowIndex + 1), item.occurredOn().toString(), item.category(), item.equipmentCode(),
                            item.equipmentName(), item.phenomenon(), item.handlingMethod(), item.rootCause(),
                            item.longTermAction() == null ? "" : item.longTermAction(), item.reporterName(),
                            item.reviewerName() == null ? "" : item.reviewerName(), item.reviewedAt() == null ? "" :
                            item.reviewedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    };
                    for (int column = 0; column < values.length; column++) {
                        Cell cell = row.createCell(column); cell.setCellValue(values[column]); cell.setCellStyle(bodyStyle);
                    }
                }
                int[] widths = {7, 12, 14, 14, 20, 42, 42, 42, 42, 14, 14, 20};
                for (int index = 0; index < widths.length; index++) sheet.setColumnWidth(index, widths[index] * 256);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("设备异常档案导出失败", ex);
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        Font font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private CellStyle bodyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setBorderBottom(BorderStyle.HAIR);
        return style;
    }

    private String uniqueSheetName(Workbook workbook, String baseName) {
        String candidate = baseName.isBlank() ? "设备" : baseName;
        for (int suffix = 2; workbook.getSheet(candidate) != null; suffix++) {
            String tail = "-" + suffix;
            candidate = baseName.substring(0, Math.min(baseName.length(), 31 - tail.length())) + tail;
        }
        return candidate;
    }
}
