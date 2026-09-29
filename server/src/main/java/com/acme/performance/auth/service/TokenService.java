package com.acme.performance.auth.service;

import com.acme.performance.auth.model.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class TokenService {
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofDays(30);
    private final JdbcClient jdbc;

    public TokenService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public IssuedToken issue(UUID userId) {
        String rawToken = UUID.randomUUID() + "." + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(ACCESS_TOKEN_TTL);
        jdbc.sql("INSERT INTO access_token(id, user_id, token_hash, expires_at) VALUES (:id, :userId, :hash, :expiresAt)")
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("hash", hash(rawToken))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
        return new IssuedToken(rawToken, expiresAt);
    }

    @Transactional(readOnly = true)
    public Optional<CurrentUser> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256) return Optional.empty();
        Optional<UserRow> user = jdbc.sql("""
                SELECT u.id, u.employee_no, u.display_name, u.org_unit_id, u.openid IS NOT NULL AS wechat_bound,
                       u.is_administrator,u.password_change_required
                FROM access_token t JOIN app_user u ON u.id = t.user_id
                WHERE t.token_hash = :hash AND t.revoked_at IS NULL AND t.expires_at > CURRENT_TIMESTAMP
                  AND u.status = 'ACTIVE'
                """)
                .param("hash", hash(rawToken))
                .query((rs, rowNum) -> new UserRow(
                        rs.getObject("id", UUID.class), rs.getString("employee_no"),
                        rs.getString("display_name"), rs.getObject("org_unit_id", UUID.class),
                        rs.getBoolean("wechat_bound"),rs.getBoolean("is_administrator"),
                        rs.getBoolean("password_change_required")))
                .optional();
        return user.map(row -> new CurrentUser(row.id(), row.employeeNo(), row.displayName(), row.orgUnitId(),
                roles(row.id()), row.wechatBound(), row.administrator(), row.passwordChangeRequired()));
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256) return;
        jdbc.sql("UPDATE access_token SET revoked_at=CURRENT_TIMESTAMP WHERE token_hash=:hash AND revoked_at IS NULL")
                .param("hash", hash(rawToken)).update();
    }

    @Transactional
    public void revokeAll(UUID userId) {
        jdbc.sql("UPDATE access_token SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=:userId AND revoked_at IS NULL")
                .param("userId", userId).update();
    }

    private Set<String> roles(UUID userId) {
        return new LinkedHashSet<>(jdbc.sql("SELECT role_code FROM role_binding WHERE user_id = :userId ORDER BY role_code")
                .param("userId", userId).query(String.class).list());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record IssuedToken(String accessToken, Instant expiresAt) {}
    private record UserRow(UUID id, String employeeNo, String displayName, UUID orgUnitId, boolean wechatBound,
                           boolean administrator, boolean passwordChangeRequired) {}
}
