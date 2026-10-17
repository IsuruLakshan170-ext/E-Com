package com.ecom.user;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.common.web.PageResponse;
import com.ecom.security.CurrentUser;
import com.ecom.user.UserAdminService.AdminUserResponse;
import com.ecom.user.UserAdminService.RoleResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin: users")
@PreAuthorize("hasAuthority('SCOPE_users:admin')")
public class UserAdminController {

    public record ChangeRoleRequest(@NotNull Role role) {
    }

    public record ChangeStatusRequest(@NotNull Boolean enabled) {
    }

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    @Operation(operationId = "listRoles", summary = "List roles and the scopes each one grants")
    public List<RoleResponse> roles() {
        return service.roles();
    }

    @GetMapping("/users")
    @Operation(operationId = "adminListUsers", summary = "List users, optionally by role")
    public PageResponse<AdminUserResponse> list(@RequestParam(required = false) Role role,
                                                @RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return service.list(role, page, size);
    }

    @GetMapping("/users/{id}")
    @Operation(operationId = "adminGetUser", summary = "Get one user")
    public AdminUserResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Privilege changes need a real admin session; API keys can never grant roles. */
    @PatchMapping("/users/{id}/role")
    @Operation(operationId = "adminChangeUserRole",
            summary = "Change a user's role (interactive admin session only; signs the user out)")
    public AdminUserResponse changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest request) {
        return service.changeRole(CurrentUser.requireInteractiveUser().userId(), id, request.role());
    }

    @PatchMapping("/users/{id}/status")
    @Operation(operationId = "adminSetUserEnabled",
            summary = "Enable or disable a user (interactive admin session only; disabling signs them out)")
    public AdminUserResponse setStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return service.setEnabled(CurrentUser.requireInteractiveUser().userId(), id, request.enabled());
    }

    @PostMapping("/users/{id}/revoke-credentials")
    @Operation(operationId = "adminRevokeUserCredentials",
            summary = "Incident response: revoke all sessions, access tokens and API keys of a user (interactive admin only)")
    public AdminUserResponse revokeCredentials(@PathVariable UUID id) {
        return service.revokeCredentials(CurrentUser.requireInteractiveUser().userId(), id);
    }
}
