package com.acme.performance.auth.service;

import com.acme.performance.common.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.admin.service.AdminGuard;
import com.acme.performance.admin.service.AuditLogService;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

@Service
public class AdminAuthService {
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH = 256;
    private static final int MAX_FAILED_LOGINS = 5;
    private static final Pattern STRONG_PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,128}$");
    private final JdbcClient jdbc;
    private final TokenService tokenService;
    private final String bootstrapToken;
    private final AdminGuard guard;
    private final AuditLogService audit;
    private final SecureRandom random = new SecureRandom();
    private final TransactionTemplate loginAttemptTransaction;
    private final TransactionTemplate loginSuccessTransaction;

    public AdminAuthService(JdbcClient jdbc, TokenService tokenService,
                            @Value("${admin.bootstrap-token:}") String bootstrapToken, AdminGuard guard, AuditLogService audit,
                            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tokenService = tokenService;
        this.bootstrapToken = bootstrapToken;
        this.guard = guard;
        this.audit = audit;
        this.loginAttemptTransaction = new TransactionTemplate(transactionManager);
        this.loginAttemptTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.loginSuccessTransaction = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public TokenService.IssuedToken bootstrap(String suppliedToken, String username, String password,
                                               String employeeNo, String displayName) {
        if (bootstrapToken.isBlank() || suppliedToken == null ||
                !MessageDigest.isEqual(bootstrapToken.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException("BOOTSTRAP_DENIED", "初始化凭证无效", HttpStatus.FORBIDDEN);
        }
        Long existing = jdbc.sql("SELECT COUNT(*) FROM admin_credential").query(Long.class).single();
        if (existing > 0) throw new ApiException("BOOTSTRAP_COMPLETED", "管理员已经初始化", HttpStatus.CONFLICT);
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbc.sql("INSERT INTO org_unit(id,type,name) VALUES (:id,'DEPARTMENT',:name)")
                .param("id", orgId).param("name", "测试工程部").update();
        jdbc.sql("INSERT INTO app_user(id,employee_no,display_name,org_unit_id,status,is_administrator,password_change_required) VALUES (:id,:employeeNo,:displayName,:orgId,'ACTIVE',TRUE,FALSE)")
                .param("id", userId).param("employeeNo", employeeNo).param("displayName", displayName).param("orgId", orgId).update();
        jdbc.sql("INSERT INTO admin_credential(user_id,username,password_hash) VALUES (:userId,:username,:hash)")
                .param("userId", userId).param("username", username.trim()).param("hash", hash(password)).update();
        return tokenService.issue(userId);
    }

    public TokenService.IssuedToken login(String username, String password, String clientType) {
        // Commit attempt state before raising an authentication error, including when
        // this login participates in a WeChat binding transaction that later rolls back.
        LoginAttempt attempt = loginAttemptTransaction.execute(status -> verifyLoginAttempt(username, password));
        if (attempt.failure() != null) throw attempt.failure();
        return loginSuccessTransaction.execute(status -> finishLogin(attempt.userId(), clientType));
    }

    private TokenService.IssuedToken finishLogin(UUID userId, String clientType) {
        if ("WEB".equalsIgnoreCase(clientType)) {
            long allowed = jdbc.sql("""
                    SELECT COUNT(*) FROM app_user u WHERE u.id=:userId AND
                      (u.is_administrator=TRUE OR EXISTS (SELECT 1 FROM role_binding rb WHERE rb.user_id=u.id
                        AND rb.role_code IN ('ASSISTANT_ENGINEER','SUPERVISOR','DEPARTMENT_MANAGER')))
                    """).param("userId", userId).query(Long.class).single();
            if (allowed == 0) throw new ApiException("WEB_ACCESS_DENIED", "当前岗位不开放Web管理后台，请使用微信小程序", HttpStatus.FORBIDDEN);
        }
        jdbc.sql("UPDATE app_user SET last_login_at=CURRENT_TIMESTAMP WHERE id=:id").param("id", userId).update();
        return tokenService.issue(userId);
    }

    private LoginAttempt verifyLoginAttempt(String username, String password) {
        Credential credential = jdbc.sql("""
                SELECT c.user_id,c.password_hash,u.failed_login_count,u.locked_until,u.status
                FROM admin_credential c JOIN app_user u ON u.id=c.user_id
                WHERE c.username=:username OR u.employee_no=:username
                FOR UPDATE
                """)
                .param("username", username.trim())
                .query((rs, row) -> new Credential(rs.getObject("user_id", UUID.class), rs.getString("password_hash"),
                        rs.getInt("failed_login_count"),rs.getObject("locked_until", OffsetDateTime.class),
                        rs.getString("status")))
                .optional().orElse(null);
        if (credential == null || !"ACTIVE".equals(credential.status()))
            return new LoginAttempt(null, new ApiException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED));
        OffsetDateTime now = OffsetDateTime.now();
        if (credential.lockedUntil() != null && credential.lockedUntil().isAfter(now)) {
            return new LoginAttempt(null, new ApiException("ACCOUNT_LOCKED", "账号已锁定，请稍后再试或联系管理员解锁", HttpStatus.LOCKED));
        }
        if (!verify(password, credential.passwordHash())) {
            // An expired lock starts a new five-attempt window.
            int failures = (credential.lockedUntil() == null ? credential.failedLoginCount() : 0) + 1;
            OffsetDateTime lockedUntil = failures >= MAX_FAILED_LOGINS ? now.plusMinutes(30) : null;
            jdbc.sql("UPDATE app_user SET failed_login_count=:failures,locked_until=:lockedUntil WHERE id=:id")
                    .param("failures", failures).param("lockedUntil", lockedUntil).param("id", credential.userId()).update();
            if (lockedUntil != null) {
                return new LoginAttempt(null, new ApiException("ACCOUNT_LOCKED", "密码连续错误5次，账号已锁定30分钟", HttpStatus.LOCKED));
            }
            return new LoginAttempt(null, new ApiException("INVALID_CREDENTIALS", "用户名或密码错误，还可尝试" + (MAX_FAILED_LOGINS - failures) + "次", HttpStatus.UNAUTHORIZED));
        }
        jdbc.sql("UPDATE app_user SET failed_login_count=0,locked_until=NULL WHERE id=:id")
                .param("id", credential.userId()).update();
        return new LoginAttempt(credential.userId(), null);
    }

    @Transactional
    public TokenService.IssuedToken changePassword(UUID userId, String currentPassword, String newPassword) {
        if (newPassword == null || !STRONG_PASSWORD.matcher(newPassword).matches()) {
            throw new ApiException("WEAK_PASSWORD", "新密码至少8位，并且必须同时包含字母和数字", HttpStatus.BAD_REQUEST);
        }
        Credential credential = jdbc.sql("""
                SELECT c.user_id,c.password_hash,u.failed_login_count,u.locked_until,u.status
                FROM admin_credential c JOIN app_user u ON u.id=c.user_id WHERE c.user_id=:userId
                """)
                .param("userId", userId)
                .query((rs, row) -> new Credential(rs.getObject("user_id", UUID.class), rs.getString("password_hash"),
                        rs.getInt("failed_login_count"),rs.getObject("locked_until", OffsetDateTime.class),rs.getString("status")))
                .optional().orElse(null);
        if (credential == null || !verify(currentPassword, credential.passwordHash()))
            throw new ApiException("INVALID_CREDENTIALS", "当前密码错误", HttpStatus.UNAUTHORIZED);
        if (MessageDigest.isEqual(currentPassword.getBytes(StandardCharsets.UTF_8), newPassword.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException("PASSWORD_UNCHANGED", "新密码不能与当前密码相同", HttpStatus.BAD_REQUEST);
        jdbc.sql("UPDATE admin_credential SET password_hash=:hash,updated_at=CURRENT_TIMESTAMP WHERE user_id=:userId")
                .param("hash", hash(newPassword)).param("userId", userId).update();
        jdbc.sql("UPDATE app_user SET password_change_required=FALSE,failed_login_count=0,locked_until=NULL WHERE id=:userId")
                .param("userId", userId).update();
        tokenService.revokeAll(userId);
        return tokenService.issue(userId);
    }

    @Transactional
    public void provision(CurrentUser actor, UUID userId, String username, String password, String requestId) {
        guard.requireSystemAdmin(actor);
        Long userExists = jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id=:id AND status='ACTIVE'").param("id", userId).query(Long.class).single();
        if (userExists == 0) throw new ApiException("USER_NOT_FOUND", "有效员工不存在", HttpStatus.NOT_FOUND);
        try {
            int updated = jdbc.sql("UPDATE admin_credential SET username=:username,password_hash=:hash,updated_at=CURRENT_TIMESTAMP WHERE user_id=:userId")
                    .param("username", username.trim()).param("hash", hash(password)).param("userId", userId).update();
            if (updated == 0) jdbc.sql("INSERT INTO admin_credential(user_id,username,password_hash) VALUES (:userId,:username,:hash)")
                    .param("userId", userId).param("username", username.trim()).param("hash", hash(password)).update();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new ApiException("ADMIN_USERNAME_EXISTS", "后台用户名已经存在", HttpStatus.CONFLICT);
        }
        tokenService.revokeAll(userId);
        audit.record(actor.userId(), "WEB_CREDENTIAL_RESET", "APP_USER", userId, "{}", requestId);
    }

    private String hash(String password) {
        byte[] salt = new byte[16]; random.nextBytes(salt);
        return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    private boolean verify(String password, String encoded) {
        try {
            String[] parts = encoded.split(":", 3); int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]); byte[] expected = Base64.getDecoder().decode(parts[2]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (RuntimeException ex) { return false; }
    }
    private byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception ex) { throw new IllegalStateException("密码哈希不可用", ex); }
        finally { spec.clearPassword(); }
    }
    public String encodePassword(String password) { return hash(password); }

    private record Credential(UUID userId, String passwordHash, int failedLoginCount,
                              OffsetDateTime lockedUntil, String status) {}
    private record LoginAttempt(UUID userId, ApiException failure) {}
}
