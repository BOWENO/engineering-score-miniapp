package com.acme.performance.attachment.service;

import com.acme.performance.common.api.ApiException;
import com.acme.performance.organization.service.DataScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AttachmentServiceTest {
    private final AttachmentService service = new AttachmentService(
            mock(S3Client.class), mock(JdbcClient.class), mock(DataScopeService.class), "test-bucket");

    @Test
    void rejectsExecutableContentType() {
        var file = new MockMultipartFile("file", "payload.exe", "application/octet-stream", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> service.upload(java.util.UUID.randomUUID(), file))
                .isInstanceOf(ApiException.class).hasMessageContaining("仅支持");
    }

    @Test
    void rejectsFilesOverTenMegabytes() {
        var file = new MockMultipartFile("file", "large.jpg", "image/jpeg", new byte[10 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> service.upload(java.util.UUID.randomUUID(), file))
                .isInstanceOf(ApiException.class).hasMessageContaining("不超过10MB");
    }
}
