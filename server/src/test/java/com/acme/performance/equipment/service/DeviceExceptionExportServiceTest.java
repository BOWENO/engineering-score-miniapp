package com.acme.performance.equipment.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceExceptionExportServiceTest {
    @Test
    void createsOneWorksheetPerDeviceWithStandardColumns() throws Exception {
        UUID firstDevice = UUID.randomUUID();
        UUID secondDevice = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        var first = view(firstDevice, "EQ-01", "测试设备一", now);
        var second = view(secondDevice, "EQ-02", "测试设备二", now);

        byte[] content = new DeviceExceptionExportService().export(List.of(first, second));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("序号");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(11).getStringCellValue()).isEqualTo("审核时间");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(5).getStringCellValue()).isEqualTo("无法启动");
        }
    }

    private DeviceExceptionService.ExceptionView view(UUID deviceId, String code, String name, OffsetDateTime now) {
        return new DeviceExceptionService.ExceptionView(UUID.randomUUID(), deviceId, code, name, "测试设备",
                UUID.randomUUID(), "整机", UUID.randomUUID(), "T001", "技术员", UUID.randomUUID(),
                LocalDate.of(2026, 8, 24), "无法启动", "重新插接线路", "接头松动", "更换防松接头",
                "ARCHIVED", UUID.randomUUID(), "主管", "通过", now, now, 1, now);
    }
}
