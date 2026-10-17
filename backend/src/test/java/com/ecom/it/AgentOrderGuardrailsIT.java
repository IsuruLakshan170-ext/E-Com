package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ecom.apikey.AgentLimits;
import com.ecom.cart.CartDtos.AddCartItemRequest;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.finance.FinanceDtos.RecordPaymentRequest;
import com.ecom.finance.PaymentMethod;
import com.ecom.order.ApprovalState;
import com.ecom.order.OrderDtos.CheckoutRequest;
import com.ecom.order.OrderDtos.OrderResponse;
import com.ecom.order.OrderStatus;
import com.ecom.user.Role;
import com.ecom.user.User;

/**
 * Agent guardrails end to end (backend/REVIEW.md §5.3): spending limits, human-in-the-loop approval, and the
 * "what did my agent do" activity feed. Uses a real API key via {@link #apiKeyFor} and runs the agent's own
 * actions as that key via {@link #asApiKey}, so {@code AgentOrderPolicy} actually sees {@code ActorType.API_KEY}
 * — {@link #as} alone (JWT) never does.
 */
class AgentOrderGuardrailsIT extends IntegrationTestBase {

    private static final Set<String> SHOPPING_SCOPES = Set.of("cart:read", "cart:write", "orders:read", "orders:write");

    /**
     * The whole flow — add to cart, quote, checkout — runs under the AGENT's own context, matching a real
     * MCP-driven purchase: the agent calls quoteCart itself with the same key, not a human session.
     */
    private OrderResponse agentCheckout(User customer, UUID keyId, UUID productId, int quantity) {
        return asApiKey(customer, keyId, SHOPPING_SCOPES, () -> {
            carts.addItem(customer.getId(), new AddCartItemRequest(productId, quantity));
            long total = carts.quote(customer.getId(), "US").total().amountMinor();
            return orders.checkout(customer.getId(), UUID.randomUUID().toString(),
                    new CheckoutRequest(address(), total)).order();
        });
    }

    @Test
    void anOrderAboveTheKeysValueLimitIsRefusedBeforeAnyStockIsReserved() {
        User admin = user(Role.ADMIN);
        User customer = user(Role.CUSTOMER);
        UUID productId = product(admin, 5, 100, 1_000); // price 1000 minor
        UUID keyId = apiKeyFor(customer, SHOPPING_SCOPES, new AgentLimits(500L, null, null));

        assertThatThrownBy(() -> agentCheckout(customer, keyId, productId, 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.AGENT_LIMIT_EXCEEDED));

        assertThat(reserved(productId)).isZero(); // refused before inventory.reserve ran
        assertThat(auditCount("AGENT_LIMIT_BLOCKED", keyId)).isEqualTo(1);
    }

    @Test
    void aKeyCannotPlaceMoreOrdersThanItsDailyCap() {
        User admin = user(Role.ADMIN);
        User customer = user(Role.CUSTOMER);
        UUID productId = product(admin, 5, 100, 1_000);
        UUID keyId = apiKeyFor(customer, SHOPPING_SCOPES, new AgentLimits(null, 1, null));

        agentCheckout(customer, keyId, productId, 1); // first order: within the cap

        assertThatThrownBy(() -> agentCheckout(customer, keyId, productId, 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.AGENT_LIMIT_EXCEEDED));
        assertThat(auditCount("AGENT_LIMIT_BLOCKED", keyId)).isEqualTo(1);
    }

    @Test
    void anOrderAboveTheApprovalThresholdWaitsForTheOwnerBeforeItCanBePaid() {
        User admin = user(Role.ADMIN);
        User customer = user(Role.CUSTOMER);
        User financeUser = user(Role.FINANCE_MANAGER);
        UUID productId = product(admin, 5, 100, 1_000);
        UUID keyId = apiKeyFor(customer, SHOPPING_SCOPES, new AgentLimits(null, null, 500L));

        OrderResponse placed = agentCheckout(customer, keyId, productId, 1);
        assertThat(placed.approvalState()).isEqualTo(ApprovalState.PENDING);
        assertThat(placed.allowedActions()).contains("approve", "reject");
        assertThat(auditCount("ORDER_AWAITING_APPROVAL", placed.id())).isEqualTo(1);

        assertThatThrownBy(() -> as(financeUser, () -> finance.recordPayment(placed.id(),
                new RecordPaymentRequest(PaymentMethod.CARD, placed.total().amountMinor(), "it"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        OrderResponse approved = as(customer, () -> orders.approveAgentOrder(customer.getId(), placed.id()));
        assertThat(approved.approvalState()).isEqualTo(ApprovalState.APPROVED);
        assertThat(auditCount("AGENT_ORDER_APPROVED", placed.id())).isEqualTo(1);

        as(financeUser, () -> finance.recordPayment(placed.id(),
                new RecordPaymentRequest(PaymentMethod.CARD, placed.total().amountMinor(), "it")));
        assertThat(orderRepository.findById(placed.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void rejectingAnAgentOrderCancelsItAndReleasesTheStock() {
        User admin = user(Role.ADMIN);
        User customer = user(Role.CUSTOMER);
        UUID productId = product(admin, 5, 100, 1_000);
        UUID keyId = apiKeyFor(customer, SHOPPING_SCOPES, new AgentLimits(null, null, 500L));

        OrderResponse placed = agentCheckout(customer, keyId, productId, 1);
        assertThat(reserved(productId)).isEqualTo(1);

        OrderResponse rejected = as(customer, () -> orders.rejectAgentOrder(customer.getId(), placed.id()));
        assertThat(rejected.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(rejected.approvalState()).isEqualTo(ApprovalState.REJECTED);
        assertThat(reserved(productId)).isZero();
        assertThat(auditCount("AGENT_ORDER_REJECTED", placed.id())).isEqualTo(1);
    }

    @Test
    void theActivityFeedShowsWhatOneKeyDidAndOnlyItsOwnerCanReadIt() {
        User admin = user(Role.ADMIN);
        User customer = user(Role.CUSTOMER);
        User someoneElse = user(Role.CUSTOMER);
        UUID productId = product(admin, 5, 100, 1_000);
        UUID keyId = apiKeyFor(customer, SHOPPING_SCOPES, AgentLimits.NONE);

        agentCheckout(customer, keyId, productId, 1);

        var activity = as(customer, () -> apiKeys.activity(customer.getId(), keyId, 0, 50));
        assertThat(activity.items()).extracting(e -> e.action().name()).contains("ORDER_PLACED");

        assertThatThrownBy(() -> as(someoneElse, () -> apiKeys.activity(someoneElse.getId(), keyId, 0, 50)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
