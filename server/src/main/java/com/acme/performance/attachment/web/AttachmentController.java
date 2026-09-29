package com.acme.performance.attachment.web;

import com.acme.performance.attachment.service.AttachmentService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {
    private final AttachmentService service;

    public AttachmentController(AttachmentService service) { this.service = service; }

    @PostMapping(consumes = "multipart/form-data")
    ApiResponse<AttachmentService.UploadedAttachment> upload(CurrentUser user, @RequestPart("file") MultipartFile file,
                                                              HttpServletRequest request) {
        return ApiResponse.success(service.upload(user.userId(), file),
                String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }

    @GetMapping("/{id}/content")
    ResponseEntity<byte[]> download(CurrentUser user, @PathVariable java.util.UUID id) {
        var file = service.download(user, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(org.springframework.http.MediaType.parseMediaType(file.mimeType())).body(file.content());
    }
}
