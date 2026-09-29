package com.acme.performance.common.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {
    private final JdbcClient jdbc;

    public IdempotencyService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StartResult start(UUID userId, String key, String method, String path) {
        return start(userId,key,method,path,null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StartResult start(UUID userId, String key, String method, String path, String hash) {
        int inserted = jdbc.sql("""
                    INSERT INTO idempotency_request(id,user_id,idempotency_key,request_method,request_path,status,request_hash)
                    VALUES (:id,:userId,:key,:method,:path,'PROCESSING',:hash)
                    ON CONFLICT (user_id,idempotency_key) DO NOTHING
                    """).param("id", UUID.randomUUID()).param("userId", userId).param("key", key)
                    .param("method", method).param("path", path).param("hash",hash).update();
        if (inserted == 1) return StartResult.started();
        StoredResponse stored = find(userId, key).orElseThrow();
        String previousHash=jdbc.sql("SELECT request_hash FROM idempotency_request WHERE user_id=:userId AND idempotency_key=:key").param("userId",userId).param("key",key).query(String.class).optional().orElse(null);
        if(!java.util.Objects.equals(hash,previousHash))return StartResult.conflict();
        if (!stored.method().equals(method) || !stored.path().equals(path)) return StartResult.conflict();
        return "COMPLETED".equals(stored.status()) ? StartResult.completed(stored) : StartResult.processing();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID userId, String key, int responseStatus, String contentType, byte[] responseBody) {
        jdbc.sql("""
                UPDATE idempotency_request SET status='COMPLETED',response_status=:responseStatus,
                  response_content_type=:contentType,response_body=:responseBody,completed_at=CURRENT_TIMESTAMP
                WHERE user_id=:userId AND idempotency_key=:key AND status='PROCESSING'
                """).param("responseStatus", responseStatus).param("contentType", contentType)
                .param("responseBody", responseBody).param("userId", userId).param("key", key).update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(UUID userId, String key) {
        jdbc.sql("DELETE FROM idempotency_request WHERE user_id=:userId AND idempotency_key=:key AND status='PROCESSING'")
                .param("userId", userId).param("key", key).update();
    }

    private Optional<StoredResponse> find(UUID userId, String key) {
        return jdbc.sql("""
                SELECT request_method,request_path,status,response_status,response_content_type,response_body,created_at
                FROM idempotency_request WHERE user_id=:userId AND idempotency_key=:key
                """).param("userId", userId).param("key", key).query((rs, n) -> new StoredResponse(
                rs.getString("request_method"), rs.getString("request_path"), rs.getString("status"),
                (Integer) rs.getObject("response_status"), rs.getString("response_content_type"),
                rs.getBytes("response_body"), rs.getObject("created_at", OffsetDateTime.class))).optional();
    }

    public enum StartState { STARTED, COMPLETED, PROCESSING, CONFLICT }
    public record StartResult(StartState state, StoredResponse response) {
        static StartResult started() { return new StartResult(StartState.STARTED, null); }
        static StartResult completed(StoredResponse response) { return new StartResult(StartState.COMPLETED, response); }
        static StartResult processing() { return new StartResult(StartState.PROCESSING, null); }
        static StartResult conflict() { return new StartResult(StartState.CONFLICT, null); }
    }
    public record StoredResponse(String method, String path, String status, Integer responseStatus,
                                 String contentType, byte[] responseBody, OffsetDateTime createdAt) {}
}
