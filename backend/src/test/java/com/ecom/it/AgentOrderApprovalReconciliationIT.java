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
import com.ecom.finance.FinanceDtos.PaymentResponse;
import com.ecom.finance.PaymentOutcome;
import com.ecom.order.ApprovalState;
import com.ecom.order.CancelReason;
import com.ecom.order.CustomerOrder;
import com.ecom.order.OrderDtos.CheckoutRequest;
import com.ecom.order.OrderDtos.OrderResponse;
import com.ecom.order.OrderStatus;
import com.ecom.user.Role;
import com.ecom.user.User;

/**
 * Review round 2 (mcp-server/REVIEW.md is the MCP thread; this is backend/REVIEW.md §12–13): A1 (approval state
 * must not outlive the order — a cancelled/expired order left PENDING stayed in the queue, could still be
 * "approved", and blocked recordPayment's own late-payment rules) and A2 (a zero-total order that needed
 * approval was never settled on approve).
 */
class AgentOrderApprovalReconciliationIT extends IntegrationTestBase {

    private static final Set<String> SHOPPING_SCOPES = Set.of("cart:read", "cart:write", "orders:read", "orders:write");

    private OrderResponse agentCheckout(User customer, UUID keyId, UUID productId, int quantity) {
        return asApiKey(customer, keyId, SHOPPING_SCOPES, () -> {
            carts.addItem(customer.getId(), new AddCartItemRequest(productId, quantity));
            long total = carts.quote(customer.getId(), "US").total().amountMinor();
            return orders.checkout(customer.getId(), UUID.randomUUID().toString(),
                    new CheckoutRequest(address(), total)).order();
        });
    }

    @Test
    void anOrderThatExpiresWhileAwaitingApprovalIsReconciledAndStillAcceptsALatePayment() {
        User admin = user(Role.ADMIN);
        User customer = user(Role.CUSTOMER);
        User financeUser = user(Role.FINANCE_MANAGER);
        UUID productId = product(admin, 5, 100, 1_000);
        UUID keyId = apiKeyFor(customer, SHOPPING_SCOPES, new AgentLimits(null, null, 500L));

        OrderResponse placed = agentCheckout(customer, keyId, productId, 1);
        assertThat(placed.approvalState()).isEqualTo(ApprovalState.PENDING);

        makeOverdue(placed.id());
        assertThat(expire(placed.id())).isTrue();

        CustomerOrder expired = orderRepository.findById(placed.id()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(expired.getCancelReason()).isEqualTo(CancelReason.EXPIRED);
        assertThat(expired.getApprovalState()).isEqualTo(ApprovalState.EXPIRED); // A1: not left PENDING
        assertThat(reserved(productId)).isZero(); // stock released like any other expiry

        // No longer in the queue, and no longer approvable.
        assertThat(as(customer, () -> orders.listAwaitingApproval(customer.getId(), 0, 20)).items())
                .noneMatch(o -> o.id().equals(placed.id()));
        assertThatThrownBy(() -> as(customer, () -> orders.approveAgentOrder(customer.getId(), placed.id())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        // The exact regression A1 describes: this must follow R3's late-payment reinstatement, not be refused
        // as "still waiting for approval".
        PaymentResponse payment = pay(financeUser, placed.id());
        assertThat(payment.outcome()).isEqualTo(PaymentOutcome.APPLIED_AFTER_REINSTATEMENT);
        assertThat(orderRepository.findById(placed.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void theCustomerCancellingTheirOwnPendingApprovalOrderReconcilesItToRejected() {
        User admin = user(Role.ADMIN);
        User customer = user(Role.CUSTOMER);
        UUID productId = product(admin, 5, 100, 1_000);
        UUID keyId = apiKeyFor(customer, SHOPPING_SCOPES, new AgentLimits(null, null, 500L));

        OrderResponse placed = agentCheckout(customer, keyId, productId, 1);

        // A customer can cancel their own order the normal way even while it awaits their own approval —
        // that's a simpler action than "reject" and should work exactly the same.
        OrderResponse cancelled = as(customer, () -> orders.cancelForUser(customer.getId(), placed.id()));

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.CUSTOMER);
        assertThat(cancelled.approvalState()).isEqualTo(ApprovalState.REJECTED); // not left PENDING
        assertThat(as(customer, () -> orders.listAwaitingApproval(customer.getId(), 0, 20)).items())
                .noneMatch(o -> o.id().equals(placed.id()));
    }

    @Test
    void approvingAZeroTotalOrderSettlesItImmediately() {
        // A2: requireApprovalAboveMinor is @PositiveOrZero, so a genuinely free order can never actually
        // trigger approval via checkout (0 is never "above" a non-negative threshold) — verified by inspection,
        // not assumed. This exercises the fix as closing the state machine, not a reachable-today scenario:
        // forced directly at the row level to simulate "as if" a zero-total order had needed approval.
        User admin = user(Role.ADMIN);
        User customer = user(Role.CUSTOMER);
        UUID productId = product(admin, 5, 0, 0); // a free product: price 0
        UUID keyId = apiKeyFor(customer, SHOPPING_SCOPES, AgentLimits.NONE);

        OrderResponse placed = agentCheckout(customer, keyId, productId, 1);
        assertThat(placed.total().amountMinor()).isZero();
        assertThat(placed.status()).isEqualTo(OrderStatus.PAID); // settles normally today, never actually PENDING

        jdbc.update("update orders set status = 'PENDING_PAYMENT', approval_state = 'PENDING', "
                + "placed_by_api_key_id = ?, paid_at = null where id = ?", keyId, placed.id());

        OrderResponse approved = as(customer, () -> orders.approveAgentOrder(customer.getId(), placed.id()));

        assertThat(approved.status()).isEqualTo(OrderStatus.PAID); // A2: settled on approve, not left stuck
        assertThat(approved.approvalState()).isEqualTo(ApprovalState.APPROVED);
    }
}
