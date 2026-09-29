package com.acme.performance.approval.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ApprovalView(UUID approvalId, long version, UUID applicationId, UUID applicantId,
                           String applicantName, String employeeNo, String ruleCode, String ruleTitle,
                           BigDecimal score, String description, OffsetDateTime occurredAt,
                           String status, List<AttachmentItem> attachments) {
    public record AttachmentItem(UUID id, String mimeType, long size, String sha256) {}
}
