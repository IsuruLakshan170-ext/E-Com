package com.ecom.security;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * L6: the identity used by scheduled jobs (and, in Phase 3, payment webhooks). The SYSTEM user row is
 * seeded by V3, is disabled, and has no usable password, so it can never be logged into.
 */
public final class SystemActor {

    public static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SystemActor() {
    }

    public static <T> T call(Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new SystemAuthentication());
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    public static final class SystemAuthentication extends AbstractAuthenticationToken {

        private final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(USER_ID, ActorType.SYSTEM, null);

        SystemAuthentication() {
            super(List.of());
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public AuthenticatedPrincipal getPrincipal() {
            return principal;
        }
    }
}
