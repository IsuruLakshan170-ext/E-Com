package com.ecom.config;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.oas.models.Operation;

/**
 * MCP review M2 (mcp-server/REVIEW.md): publishes each operation's required scope(s) as the
 * {@code x-required-scopes} OpenAPI extension, read directly from the same {@link PreAuthorize}
 * annotation that actually enforces it at request time — never a second, separately maintained
 * list that could drift from the real check. An MCP client calls {@code whoAmI} once and shows an
 * agent only the tools its key's scopes genuinely allow.
 *
 * <p>G1 (code review): an operation with no {@code @PreAuthorize} is NOT assumed safe to expose.
 * It gets {@code x-mcp-unscoped-ok: true} only if its operationId is in
 * {@link #UNSCOPED_ALLOWED_OPERATIONS} (public catalog reads and the identity check); otherwise it
 * gets neither extension, so the MCP server's deny-by-default policy
 * (mcp-server/src/ecom_mcp/policy.py) excludes it. This is deliberate for session/credential
 * operations (register, login, changeMyPassword, API-key management — gated by actor type, not a
 * scope) — those are additionally named on the MCP server's own {@code EXCLUDED_OPERATIONS}, which
 * is the list a reader should extend for a *new* unscoped operation that must still never be an
 * agent tool. Adding an operationId to {@link #UNSCOPED_ALLOWED_OPERATIONS} is for the opposite
 * case: a new unscoped operation that genuinely is safe for any agent to call.
 *
 * <p>G2: a {@code @PreAuthorize} value that isn't a plain {@code hasAuthority}/{@code
 * hasAnyAuthority} call over {@code SCOPE_xxx} literals throws immediately instead of silently
 * publishing wrong, partial, or no scopes. Every {@code @PreAuthorize} in this codebase was one of
 * those two forms when this was written (verified against every controller;
 * {@code ScopeExtensionCoverageTest} keeps that true going forward).
 */
@Component
public class ScopeExtensionOperationCustomizer implements OperationCustomizer {

    static final String REQUIRED_SCOPES_EXTENSION = "x-required-scopes";
    static final String UNSCOPED_OK_EXTENSION = "x-mcp-unscoped-ok";

    /** Public reads and the identity check: safe for any agent to call regardless of scope. */
    static final Set<String> UNSCOPED_ALLOWED_OPERATIONS =
            Set.of("whoAmI", "searchProducts", "getProduct", "listCategories", "getCategory");

    private static final Pattern SUPPORTED_EXPRESSION = Pattern.compile(
            "^(?:hasAuthority|hasAnyAuthority)\\(\\s*'SCOPE_[a-zA-Z0-9:_-]+'\\s*"
                    + "(?:,\\s*'SCOPE_[a-zA-Z0-9:_-]+'\\s*)*\\)$");
    private static final Pattern QUOTED_SCOPE = Pattern.compile("'SCOPE_([a-zA-Z0-9:_-]+)'");

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        Set<String> scopes = scopesOf(handlerMethod.getMethod(), handlerMethod.getBeanType());
        if (!scopes.isEmpty()) {
            operation.addExtension(REQUIRED_SCOPES_EXTENSION, List.copyOf(scopes));
        } else if (UNSCOPED_ALLOWED_OPERATIONS.contains(operation.getOperationId())) {
            operation.addExtension(UNSCOPED_OK_EXTENSION, true);
        }
        return operation;
    }

    /** Method-level {@code @PreAuthorize} overrides class-level entirely (Spring Security never merges them). */
    static Set<String> scopesOf(Method method, Class<?> controllerType) {
        PreAuthorize onMethod = method.getAnnotation(PreAuthorize.class);
        if (onMethod != null) {
            return scopesIn(onMethod.value());
        }
        PreAuthorize onClass = controllerType.getAnnotation(PreAuthorize.class);
        return onClass == null ? Set.of() : scopesIn(onClass.value());
    }

    /**
     * Pulls every {@code SCOPE_xxx} literal out of a hasAuthority/hasAnyAuthority SpEL expression.
     * The whole expression must match one of those two forms or this throws (G2) — a regex over
     * the expression string rather than a full SpEL evaluation, since every real
     * {@code @PreAuthorize} here is one of those two forms and a full SpEL parser would be a lot
     * of machinery for a doc-generation nicety that never gates a real request either way.
     */
    static Set<String> scopesIn(String expression) {
        String trimmed = expression.trim();
        if (!SUPPORTED_EXPRESSION.matcher(trimmed).matches()) {
            throw new IllegalStateException(
                    "Cannot derive x-required-scopes from @PreAuthorize(\"" + expression + "\"): only "
                            + "hasAuthority('SCOPE_x') and hasAnyAuthority('SCOPE_a', 'SCOPE_b', ...) are "
                            + "understood. Either rewrite the expression in one of those forms, or extend "
                            + "ScopeExtensionOperationCustomizer to understand the new one deliberately — "
                            + "never let a scope requirement go unpublished or silently wrong.");
        }
        Set<String> scopes = new LinkedHashSet<>();
        Matcher quoted = QUOTED_SCOPE.matcher(trimmed);
        while (quoted.find()) {
            scopes.add(quoted.group(1));
        }
        return scopes;
    }
}
