package com.ecom.user;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.web.PageResponse;
import com.ecom.security.SystemActor;
import com.ecom.security.UserTokenStateCache;

@Service
public class UserAdminService {

    public record AdminUserResponse(UUID id, String email, String fullName, Role role, List<String> scopes,
                                    boolean enabled, Instant createdAt, Instant updatedAt) {
        static AdminUserResponse from(User u) {
            return new AdminUserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getRole().scopes(),
                    u.isEnabled(), u.getCreatedAt(), u.getUpdatedAt());
        }
    }

    public record RoleResponse(Role role, List<String> scopes) {
    }

    private final UserRepository users;
    private final AuthService authService;
    private final UserTokenStateCache tokenStateCache;
    private final AuditService audit;

    public UserAdminService(UserRepository users, AuthService authService, UserTokenStateCache tokenStateCache,
                            AuditService audit) {
        this.users = users;
        this.authService = authService;
        this.tokenStateCache = tokenStateCache;
        this.audit = audit;
    }

    public List<RoleResponse> roles() {
        return Arrays.stream(Role.values()).map(r -> new RoleResponse(r, r.scopes())).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(Role role, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("email"));
        return PageResponse.of((role == null ? users.findAll(pageable) : users.findByRole(role, pageable))
                .map(AdminUserResponse::from));
    }

    @Transactional(readOnly = true)
    public AdminUserResponse get(UUID id) {
        return users.findById(id).map(AdminUserResponse::from).orElseThrow(() -> ApiException.notFound("User"));
    }

    /** Role changes invalidate existing access tokens immediately (token version) and sign the user out. */
    @Transactional
    public AdminUserResponse changeRole(UUID actorId, UUID userId, Role newRole) {
        User user = editable(actorId, userId, "change your own role");
        Role from = user.getRole();
        if (from == newRole) {
            return AdminUserResponse.from(user);
        }
        guardLastAdmin(user);
        user.changeRole(newRole);
        authService.revokeEverything(userId, RevokeReason.ROLE_CHANGED);
        audit.record(AuditAction.USER_ROLE_CHANGED, "user", userId, from + "->" + newRole);
        return AdminUserResponse.from(user);
    }

    @Transactional
    public AdminUserResponse setEnabled(UUID actorId, UUID userId, boolean enabled) {
        User user = editable(actorId, userId, "enable or disable your own account");
        if (user.isEnabled() == enabled) {
            return AdminUserResponse.from(user);
        }
        if (!enabled) {
            guardLastAdmin(user);
        }
        user.setEnabled(enabled);
        if (enabled) {
            // R9: drop the cached "disabled" state at commit, so the user's first new token works immediately.
            tokenStateCache.evictAfterCommit(userId);
        } else {
            authService.revokeEverything(userId, RevokeReason.DISABLED);
        }
        audit.record(enabled ? AuditAction.USER_ENABLED : AuditAction.USER_DISABLED, "user", userId, null);
        return AdminUserResponse.from(user);
    }

    /** H3: incident response - kill every session, access token and API key of a (possibly compromised) user. */
    @Transactional
    public AdminUserResponse revokeCredentials(UUID actorId, UUID userId) {
        User user = editable(actorId, userId, "revoke your own credentials here (use revokeAllMyCredentials)");
        user.bumpTokenVersion();
        authService.revokeEverything(userId, RevokeReason.CREDENTIALS_REVOKED);
        audit.record(AuditAction.CREDENTIALS_REVOKED, "user", userId, "by admin");
        return AdminUserResponse.from(user);
    }

    private User editable(UUID actorId, UUID userId, String selfMessage) {
        if (actorId.equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You cannot " + selfMessage);
        }
        if (SystemActor.USER_ID.equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "The SYSTEM account cannot be modified");
        }
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    private void guardLastAdmin(User user) {
        if (user.getRole() == Role.ADMIN && user.isEnabled() && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new ApiException(ErrorCode.CONFLICT, "Cannot remove or disable the last active admin");
        }
    }
}
