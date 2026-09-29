package com.acme.performance.admin.web;

import com.acme.performance.admin.service.OrganizationAdminService;
import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.auth.service.AdminAuthService;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class OrganizationAdminController {
    private final OrganizationAdminService service;
    private final AdminAuthService adminAuthService;
    public OrganizationAdminController(OrganizationAdminService service, AdminAuthService adminAuthService) { this.service = service; this.adminAuthService = adminAuthService; }

    @GetMapping("/organizations")
    ApiResponse<List<OrganizationAdminService.OrgItem>> organizations(CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(service.organizations(user), requestId(request));
    }

    @GetMapping("/users")
    ApiResponse<List<OrganizationAdminService.UserItem>> users(CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(service.users(user), requestId(request));
    }

    @PostMapping("/organizations")
    ApiResponse<OrganizationAdminService.OrgItem> create(CurrentUser user, @Valid @RequestBody OrgRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.createOrganization(user, body.parentId(), body.type(), body.name(), requestId(request)), requestId(request));
    }

    @PutMapping("/organizations/{id}")
    ApiResponse<OrganizationAdminService.OrgItem> updateOrganization(CurrentUser user, @PathVariable UUID id,
            @Valid @RequestBody OrgRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.updateOrganization(user, id, body.parentId(), body.type(), body.name(), requestId(request)), requestId(request));
    }

    @PostMapping("/users")
    ApiResponse<UUID> createUser(CurrentUser user, @Valid @RequestBody UserRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.createUser(user, body.employeeNo(), body.displayName(), body.orgUnitId(), requestId(request)), requestId(request));
    }

    @PutMapping("/users/{id}")
    ApiResponse<Void> updateUser(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody UserUpdateRequest body,
                                 HttpServletRequest request) {
        service.updateUser(user, id, body.displayName(), body.orgUnitId(), body.status(), requestId(request));
        return ApiResponse.success(null, requestId(request));
    }

    @PutMapping("/users/{id}/web-credential")
    ApiResponse<Void> webCredential(CurrentUser user, @PathVariable UUID id, @Valid @RequestBody WebCredentialRequest body,
                                    HttpServletRequest request) {
        adminAuthService.provision(user, id, body.username(), body.password(), requestId(request));
        return ApiResponse.success(null, requestId(request));
    }

    @PostMapping(value="/users/import", consumes="multipart/form-data")
    ApiResponse<OrganizationAdminService.ImportResult> importUsers(CurrentUser user, @RequestPart("file") MultipartFile file, HttpServletRequest request) {
        return ApiResponse.success(service.importUsers(user, file, requestId(request)), requestId(request));
    }

    @PutMapping("/users/{id}/wechat-binding")
    ApiResponse<Void> bind(CurrentUser user, @PathVariable UUID id, @RequestBody WechatBinding body, HttpServletRequest request) {
        service.bindWechat(user, id, body.openid(), requestId(request));
        return ApiResponse.success(null, requestId(request));
    }

    @DeleteMapping("/users/{id}/wechat-binding")
    ApiResponse<Void> unbind(CurrentUser user, @PathVariable UUID id, HttpServletRequest request) {
        service.unbindWechat(user, id, requestId(request));
        return ApiResponse.success(null, requestId(request));
    }

    @PostMapping("/users/{id}/credential/reset")
    ApiResponse<Void> resetCredential(CurrentUser user, @PathVariable UUID id, HttpServletRequest request) {
        service.resetCredential(user, id, requestId(request));
        return ApiResponse.success(null, requestId(request));
    }

    @PostMapping("/users/{id}/unlock")
    ApiResponse<Void> unlock(CurrentUser user, @PathVariable UUID id, HttpServletRequest request) {
        service.unlock(user, id, requestId(request));
        return ApiResponse.success(null, requestId(request));
    }

    @PostMapping("/role-bindings")
    ApiResponse<UUID> grant(CurrentUser user, @RequestBody RoleRequest body, HttpServletRequest request) {
        return ApiResponse.success(service.grantRole(user, body.userId(), body.roleCode(), body.scopeId(), requestId(request)), requestId(request));
    }

    @DeleteMapping("/role-bindings")
    ApiResponse<Void> revoke(CurrentUser user, @RequestBody RoleRequest body, HttpServletRequest request) {
        service.revokeRole(user, body.userId(), body.roleCode(), body.scopeId(), requestId(request));
        return ApiResponse.success(null, requestId(request));
    }

    private String requestId(HttpServletRequest request) { return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)); }
    public record OrgRequest(UUID parentId, @NotBlank String type, @NotBlank String name) {}
    public record WechatBinding(@NotBlank String openid) {}
    public record RoleRequest(UUID userId, @NotBlank String roleCode, UUID scopeId) {}
    public record UserRequest(@NotBlank String employeeNo, @NotBlank String displayName, UUID orgUnitId) {}
    public record UserUpdateRequest(@NotBlank String displayName, UUID orgUnitId, @NotBlank String status) {}
    public record WebCredentialRequest(@NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9._-]{3,64}") String username,
                                       @NotBlank @jakarta.validation.constraints.Size(min=8,max=128) String password) {}
}
