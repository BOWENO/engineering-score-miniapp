package com.acme.performance.attachment.service;

import com.acme.performance.common.api.ApiException;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.organization.service.DataScopeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentService {
    private static final long MAX_SIZE = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "application/pdf");
    private final S3Client s3;
    private final JdbcClient jdbc;
    private final String bucket;
    private final DataScopeService dataScope;

    public AttachmentService(S3Client s3, JdbcClient jdbc, DataScopeService dataScope,
                             @Value("${object-storage.bucket}") String bucket) {
        this.s3 = s3;
        this.jdbc = jdbc;
        this.dataScope = dataScope;
        this.bucket = bucket;
    }

    public UploadedAttachment upload(UUID ownerId, MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_SIZE) {
            throw new ApiException("INVALID_ATTACHMENT_SIZE", "附件必须大于0且不超过10MB", HttpStatus.BAD_REQUEST);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new ApiException("INVALID_ATTACHMENT_TYPE", "仅支持JPG、PNG和PDF", HttpStatus.BAD_REQUEST);
        }
        try {
            byte[] bytes = file.getBytes();
            if (!matchesSignature(contentType, bytes)) {
                throw new ApiException("INVALID_ATTACHMENT_CONTENT", "文件实际内容与声明类型不一致", HttpStatus.BAD_REQUEST);
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            UUID id = UUID.randomUUID();
            String objectKey = "evidence/" + ownerId + "/" + id;
            ensureBucket();
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType)
                    .metadata(java.util.Map.of("sha256", hash)).build(), RequestBody.fromBytes(bytes));
            jdbc.sql("""
                    INSERT INTO attachment(id,owner_id,object_key,content_hash,mime_type,size_bytes)
                    VALUES (:id,:ownerId,:objectKey,:hash,:mimeType,:size)
                    """).param("id", id).param("ownerId", ownerId).param("objectKey", objectKey)
                    .param("hash", hash).param("mimeType", contentType).param("size", bytes.length).update();
            return new UploadedAttachment(id, contentType, bytes.length, hash);
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new ApiException("ATTACHMENT_UPLOAD_FAILED", "附件处理失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean matchesSignature(String contentType, byte[] bytes) {
        if ("image/jpeg".equals(contentType)) return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        if ("image/png".equals(contentType)) {
            int[] signature={0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
            if(bytes.length<signature.length)return false;
            for(int i=0;i<signature.length;i++)if((bytes[i]&0xff)!=signature[i])return false;
            return true;
        }
        return "application/pdf".equals(contentType) && bytes.length >= 5 && bytes[0]=='%' && bytes[1]=='P'
                && bytes[2]=='D' && bytes[3]=='F' && bytes[4]=='-';
    }

    private void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) throw ex;
            try { s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build()); }
            catch (S3Exception createFailure) { if (createFailure.statusCode() != 409) throw createFailure; }
        }
    }

    public DownloadedAttachment download(CurrentUser actor, UUID attachmentId) {
        FileRow row = jdbc.sql("SELECT owner_id,object_key,mime_type FROM attachment WHERE id=:id")
                .param("id", attachmentId).query((rs, n) -> new FileRow(rs.getObject("owner_id", UUID.class),
                        rs.getString("object_key"), rs.getString("mime_type"))).optional()
                .orElseThrow(() -> new ApiException("ATTACHMENT_NOT_FOUND", "附件不存在", HttpStatus.NOT_FOUND));
        boolean evidenceReviewer = actor.roles().stream().anyMatch(Set.of("ASSISTANT_ENGINEER", "SUPERVISOR", "DEPARTMENT_MANAGER")::contains);
        if (!actor.userId().equals(row.ownerId()) && (!evidenceReviewer || !dataScope.canAccessUser(actor, row.ownerId()))) {
            throw new ApiException("FORBIDDEN", "无权读取该附件", HttpStatus.FORBIDDEN);
        }
        byte[] content = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(row.objectKey()).build()).asByteArray();
        return new DownloadedAttachment(content, row.mimeType());
    }

    public record UploadedAttachment(UUID id, String mimeType, long size, String sha256) {}
    public record DownloadedAttachment(byte[] content, String mimeType) {}
    private record FileRow(UUID ownerId, String objectKey, String mimeType) {}
}
