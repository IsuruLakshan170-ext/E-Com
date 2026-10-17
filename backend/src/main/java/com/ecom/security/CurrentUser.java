package com.ecom.security;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<AuthenticatedPrincipal> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.of(new AuthenticatedPrincipal(UUID.fromString(jwt.getToken().getSubject()), ActorType.USER, null));
        }
        if (auth instanceof ApiKeyAuthentication apiKey) {
            return Optional.of(apiKey.getPrincipal());
        }
        if (auth instanceof SystemActor.SystemAuthentication system) {
            return Optional.of(system.getPrincipal());
        }
        return Optional.empty();
    }

    public static AuthenticatedPrincipal get() {
        return find().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication required"));
    }

    public static UUID id() {
        return get().userId();
    }

    /**
     * Account-level operations (password change, API key management) are reserved for a real user
     * session, so a leaked API key can never mint more keys or take over the account.
     */
    public static AuthenticatedPrincipal requireInteractiveUser() {
        AuthenticatedPrincipal principal = get();
        if (principal.actorType() != ActorType.USER) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "This operation requires an interactive user session; API keys are not allowed");
        }
        return principal;
    }

    public static boolean hasScope(String scope) {
        return scopes().contains(scope);
    }

    public static Set<String> scopes() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> scopes = new TreeSet<>();
        if (auth != null) {
            for (GrantedAuthority authority : auth.getAuthorities()) {
                String name = authority.getAuthority();
                if (name != null && name.startsWith(Scopes.AUTHORITY_PREFIX)) {
                    scopes.add(name.substring(Scopes.AUTHORITY_PREFIX.length()));
                }
            }
        }
        return scopes;
    }
}
