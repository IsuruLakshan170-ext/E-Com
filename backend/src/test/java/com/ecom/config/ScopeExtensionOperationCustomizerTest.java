package com.ecom.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** G2 (mcp-server/REVIEW.md): the scope-parsing logic backing x-required-scopes. */
class ScopeExtensionOperationCustomizerTest {

    @Test
    void readsASingleHasAuthorityScope() {
        assertThat(ScopeExtensionOperationCustomizer.scopesIn("hasAuthority('SCOPE_finance:read')"))
                .containsExactly("finance:read");
    }

    @Test
    void readsEveryScopeInHasAnyAuthority() {
        assertThat(ScopeExtensionOperationCustomizer.scopesIn(
                        "hasAnyAuthority('SCOPE_returns:manage', 'SCOPE_inventory:write', 'SCOPE_finance:write')"))
                .containsExactlyInAnyOrder("returns:manage", "inventory:write", "finance:write");
    }

    @Test
    void toleratesExtraWhitespace() {
        assertThat(ScopeExtensionOperationCustomizer.scopesIn("  hasAuthority( 'SCOPE_orders:read' )  "))
                .containsExactly("orders:read");
    }

    @Test
    void rejectsAnythingElseInsteadOfGuessing() {
        for (String unsupported : new String[] {
            "isAuthenticated()",
            "hasAuthority('SCOPE_orders:write') and isAuthenticated()",
            "@sodGuard.check(#id)",
            "hasRole('ADMIN')",
            "permitAll()",
            "hasAuthority('orders:write')", // missing the SCOPE_ prefix the backend actually grants
        }) {
            assertThatThrownBy(() -> ScopeExtensionOperationCustomizer.scopesIn(unsupported))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(unsupported);
        }
    }
}
