package com.acme.performance.auth.service;

import com.acme.performance.common.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class WechatAuthService {
    private final RestClient restClient;
    private final JdbcClient jdbc;
    private final TokenService tokenService;
    private final AdminAuthService accountAuthService;
    private final ObjectMapper objectMapper;
    private final String appId;
    private final String appSecret;

    public WechatAuthService(RestClient restClient, JdbcClient jdbc, TokenService tokenService,
                             AdminAuthService accountAuthService, ObjectMapper objectMapper,
                             @Value("${wechat.app-id}") String appId,
                             @Value("${wechat.app-secret}") String appSecret) {
        this.restClient = restClient;
        this.jdbc = jdbc;
        this.tokenService = tokenService;
        this.accountAuthService = accountAuthService;
        this.objectMapper = objectMapper;
        this.appId = appId;
        this.appSecret = appSecret;
    }

    @Transactional
    public LoginResult login(String code) {
        WechatIdentity identity = exchange(code);
        UUID userId = jdbc.sql("SELECT id FROM app_user WHERE openid=:openid AND status='ACTIVE'")
                .param("openid", identity.openid()).query(UUID.class).optional().orElse(null);
        if (userId == null) {
            return new LoginResult(null, null, issueBindingTicket(identity), true);
        }
        jdbc.sql("UPDATE app_user SET last_login_at=CURRENT_TIMESTAMP WHERE id=:id").param("id", userId).update();
        TokenService.IssuedToken token = tokenService.issue(userId);
        return new LoginResult(token.accessToken(), token.expiresAt(), null, false);
    }

    @Transactional
    public TokenService.IssuedToken bindAccount(String bindingToken, String username, String password) {
        BindingIdentity identity = consumeBindingTicket(bindingToken);
        TokenService.IssuedToken token = accountAuthService.login(username, password, "MINI");
        UUID userId = tokenService.authenticate(token.accessToken())
                .orElseThrow(() -> new ApiException("INVALID_CREDENTIALS", "工号或密码错误", HttpStatus.UNAUTHORIZED)).userId();
        UUID otherUser = jdbc.sql("SELECT id FROM app_user WHERE openid=:openid AND id<>:userId")
                .param("openid", identity.openid()).param("userId", userId)
                .query(UUID.class).optional().orElse(null);
        if (otherUser != null) {
            throw new ApiException("OPENID_ALREADY_BOUND", "当前微信已绑定其他员工账号", HttpStatus.CONFLICT);
        }
        int updated = jdbc.sql("""
                UPDATE app_user SET openid=:openid,unionid=:unionid,version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=:userId AND status='ACTIVE' AND (openid IS NULL OR openid=:openid)
                """).param("openid", identity.openid()).param("unionid", identity.unionid())
                .param("userId", userId).update();
        if (updated != 1) {
            throw new ApiException("WECHAT_ALREADY_BOUND", "该工号已绑定其他微信，请联系管理员解绑", HttpStatus.CONFLICT);
        }
        jdbc.sql("UPDATE wechat_binding_ticket SET consumed_at=CURRENT_TIMESTAMP WHERE token_hash=:hash")
                .param("hash", hash(bindingToken)).update();
        return token;
    }

    @Transactional
    public void bind(com.acme.performance.auth.model.CurrentUser user, String code) {
        WechatIdentity identity = exchange(code);
        UUID otherUser = jdbc.sql("SELECT id FROM app_user WHERE openid=:openid AND id<>:userId")
                .param("openid", identity.openid()).param("userId", user.userId())
                .query(UUID.class).optional().orElse(null);
        if (otherUser != null) {
            throw new ApiException("OPENID_ALREADY_BOUND", "当前微信已绑定其他员工账号", HttpStatus.CONFLICT);
        }
        try {
            int updated = jdbc.sql("""
                    UPDATE app_user SET openid=:openid,unionid=:unionid,version=version+1,updated_at=CURRENT_TIMESTAMP
                    WHERE id=:userId AND status='ACTIVE' AND (openid IS NULL OR openid=:openid)
                    """).param("openid", identity.openid()).param("unionid", identity.unionid())
                    .param("userId", user.userId()).update();
            if (updated != 1) {
                throw new ApiException("WECHAT_ALREADY_BOUND", "员工账号已绑定其他微信，请联系管理员解绑", HttpStatus.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("OPENID_ALREADY_BOUND", "当前微信已绑定其他员工账号", HttpStatus.CONFLICT);
        }
    }

    @SuppressWarnings("unchecked")
    private WechatIdentity exchange(String code) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()
                || appSecret.contains("placeholder")) {
            throw new ApiException("WECHAT_CONFIG_INVALID", "服务器微信凭据尚未正确配置，请联系管理员", HttpStatus.SERVICE_UNAVAILABLE);
        }
        Map<String, Object> response;
        try {
            String responseBody = restClient.get()
                    .uri(builder -> builder.scheme("https").host("api.weixin.qq.com")
                            .path("/sns/jscode2session")
                            .queryParam("appid", appId).queryParam("secret", appSecret)
                            .queryParam("js_code", code).queryParam("grant_type", "authorization_code").build())
                    .retrieve().body(String.class);
            response = responseBody == null ? null : objectMapper.readValue(responseBody, new TypeReference<>() {});
        } catch (RestClientException ex) {
            throw new ApiException("WECHAT_SERVICE_UNAVAILABLE", "暂时无法连接微信登录服务，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (JsonProcessingException ex) {
            throw new ApiException("WECHAT_RESPONSE_INVALID", "微信登录服务返回异常，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (response == null || response.get("openid") == null) {
            String errorCode = response == null ? "unknown" : String.valueOf(response.getOrDefault("errcode", "unknown"));
            if ("40029".equals(errorCode) || "40163".equals(errorCode)) {
                throw new ApiException("WECHAT_CODE_EXPIRED", "微信登录凭证已过期，请重新点击绑定", HttpStatus.UNAUTHORIZED);
            }
            if ("40125".equals(errorCode)) {
                throw new ApiException("WECHAT_CONFIG_INVALID", "服务器微信凭据校验失败，请联系管理员", HttpStatus.SERVICE_UNAVAILABLE);
            }
            throw new ApiException("WECHAT_LOGIN_FAILED", "微信登录凭据校验失败（" + errorCode + "）", HttpStatus.UNAUTHORIZED);
        }
        Object unionid = response.get("unionid");
        return new WechatIdentity(String.valueOf(response.get("openid")), unionid == null ? null : String.valueOf(unionid));
    }

    private String issueBindingTicket(WechatIdentity identity) {
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO wechat_binding_ticket(id,token_hash,openid,unionid,expires_at)
                VALUES (:id,:hash,:openid,:unionid,:expiresAt)
                """).param("id", UUID.randomUUID()).param("hash", hash(token))
                .param("openid", identity.openid()).param("unionid", identity.unionid())
                .param("expiresAt", Timestamp.from(Instant.now().plus(Duration.ofMinutes(10)))).update();
        return token;
    }

    private BindingIdentity consumeBindingTicket(String token) {
        if (token == null || token.isBlank() || token.length() > 256) invalidTicket();
        return jdbc.sql("""
                SELECT openid,unionid FROM wechat_binding_ticket
                WHERE token_hash=:hash AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP
                FOR UPDATE
                """).param("hash", hash(token))
                .query((rs, row) -> new BindingIdentity(rs.getString("openid"), rs.getString("unionid")))
                .optional().orElseThrow(() -> new ApiException("WECHAT_BINDING_EXPIRED", "微信登录已过期，请重新一键登录", HttpStatus.UNAUTHORIZED));
    }

    private void invalidTicket() {
        throw new ApiException("WECHAT_BINDING_EXPIRED", "微信登录已过期，请重新一键登录", HttpStatus.UNAUTHORIZED);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record WechatIdentity(String openid, String unionid) {}
    private record BindingIdentity(String openid, String unionid) {}
    public record LoginResult(String accessToken, Instant expiresAt, String bindingToken, boolean bindingRequired) {}
}
