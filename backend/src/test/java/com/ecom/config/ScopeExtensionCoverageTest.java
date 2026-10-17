package com.ecom.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ecom.apikey.ApiKeyController;
import com.ecom.audit.AuditController;
import com.ecom.cart.CartController;
import com.ecom.catalog.CategoryController;
import com.ecom.catalog.ProductController;
import com.ecom.finance.CustomerInvoiceController;
import com.ecom.finance.FinanceController;
import com.ecom.inventory.InventoryController;
import com.ecom.order.AdminOrderController;
import com.ecom.order.OrderController;
import com.ecom.purchasing.PurchaseOrderController;
import com.ecom.purchasing.ReplenishmentController;
import com.ecom.purchasing.SupplierController;
import com.ecom.returns.ReturnController;
import com.ecom.tax.TaxController;
import com.ecom.user.AuthController;
import com.ecom.user.UserAdminController;
import com.ecom.user.UserController;

import io.swagger.v3.oas.annotations.Operation;

/**
 * G2 (mcp-server/REVIEW.md): mirrors the MCP server's deny-by-default policy on the backend side
 * (its G1). Every documented operation must end up with a published scope, be explicitly allowed
 * unscoped, or be a known session/credential operation the MCP server excludes outright
 * (mcp-server/src/ecom_mcp/policy.py's EXCLUDED_OPERATIONS) — nothing should fall through the
 * cracks silently when a new endpoint is added. Reflects directly over the controllers rather than
 * starting a Spring context: operationId and @PreAuthorize are both plain annotations.
 *
 * <p>If this test fails after adding a controller or an operation, that is the point: decide
 * deliberately whether it needs a scope, belongs in {@code UNSCOPED_ALLOWED_OPERATIONS}, or is a
 * new session/credential-style operation that belongs in both
 * {@link #EXPECTED_WITHOUT_ANY_EXTENSION} here and the MCP server's own {@code EXCLUDED_OPERATIONS}
 * — then update whichever list, in both places where it applies.
 */
class ScopeExtensionCoverageTest {

    /**
     * Operations with neither extension, on purpose: gated by actor type (a real user session),
     * not a scope — {@code adminChangeUserRole}/{@code adminSetUserEnabled}/
     * {@code adminRevokeUserCredentials} are NOT here, even though the MCP server also excludes
     * them (mcp-server policy.py), because they DO inherit a real {@code users:admin} scope from
     * UserAdminController's class-level @PreAuthorize and so are covered by the scope path below;
     * the MCP server's exclusion of those three is a deliberate extra step (M1), not something
     * this coverage check needs to re-derive.
     */
    private static final Set<String> EXPECTED_WITHOUT_ANY_EXTENSION =
            Set.of(
                    "register", "login", "refreshToken", "logout",
                    "changeMyPassword", "revokeAllMyCredentials",
                    "createApiKey", "listApiKeys", "revokeApiKey",
                    // Agent guardrails (REVIEW.md §5.3): the human's side of the loop. No scope on purpose —
                    // an agent key must never approve the order it just placed, nor read its own activity
                    // feed, so these are gated by actor type like the credential operations above.
                    "getApiKeyActivity", "listOrdersAwaitingApproval", "approveAgentOrder", "rejectAgentOrder");

    private static final Class<?>[] CONTROLLERS = {
        ApiKeyController.class,
        AuditController.class,
        CartController.class,
        CategoryController.class,
        ProductController.class,
        CustomerInvoiceController.class,
        FinanceController.class,
        InventoryController.class,
        AdminOrderController.class,
        OrderController.class,
        PurchaseOrderController.class,
        ReplenishmentController.class,
        SupplierController.class,
        ReturnController.class,
        TaxController.class,
        AuthController.class,
        UserAdminController.class,
        UserController.class,
    };

    @Test
    void everyOperationIsScopedOrExplicitlyAccountedFor() {
        List<String> uncovered = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                Operation operation = method.getAnnotation(Operation.class);
                if (operation == null || operation.operationId().isBlank()) {
                    continue;
                }
                String operationId = operation.operationId();
                seen.add(operationId);
                Set<String> scopes = ScopeExtensionOperationCustomizer.scopesOf(method, controller);
                boolean accounted =
                        !scopes.isEmpty()
                                || ScopeExtensionOperationCustomizer.UNSCOPED_ALLOWED_OPERATIONS.contains(operationId)
                                || EXPECTED_WITHOUT_ANY_EXTENSION.contains(operationId);
                if (!accounted) {
                    uncovered.add(operationId);
                }
            }
        }

        assertThat(seen).as("sanity check: the controller list above found no @Operation methods at all")
                .isNotEmpty();
        assertThat(uncovered)
                .as(
                        "operation(s) with no @PreAuthorize scope, not in UNSCOPED_ALLOWED_OPERATIONS, and not in "
                                + "the known EXPECTED_WITHOUT_ANY_EXTENSION list — an MCP client would deny them by "
                                + "default. Decide deliberately: add a scope, add to UNSCOPED_ALLOWED_OPERATIONS, or "
                                + "add to EXPECTED_WITHOUT_ANY_EXTENSION here AND mcp-server's EXCLUDED_OPERATIONS")
                .isEmpty();
    }

    /** A new controller file is invisible to this test until it's added to {@link #CONTROLLERS}. */
    @Test
    void controllerListStaysInSyncWithWhatActuallyExistsOnDisk() {
        assertThat(CONTROLLERS).hasSize(18);
    }
}
