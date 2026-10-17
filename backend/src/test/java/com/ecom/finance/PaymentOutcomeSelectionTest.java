package com.ecom.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.ecom.order.CancelReason;
import com.ecom.order.OrderStatus;

/** R3/R11: the outcome a payment gets from the order's state; cancelled orders are never reinstated. */
class PaymentOutcomeSelectionTest {

    private static final BooleanSupplier MUST_NOT_REINSTATE = () -> {
        throw new AssertionError("reinstatement must not be attempted");
    };

    @Test
    void anUnpaidOrderIsApplied() {
        assertThat(FinanceService.selectOutcome(OrderStatus.PENDING_PAYMENT, null, MUST_NOT_REINSTATE))
                .contains(PaymentOutcome.APPLIED);
    }

    @Test
    void anExpiredOrderIsReinstatedWhenItsStockCanBeReReserved() {
        assertThat(FinanceService.selectOutcome(OrderStatus.CANCELLED, CancelReason.EXPIRED, () -> true))
                .contains(PaymentOutcome.APPLIED_AFTER_REINSTATEMENT);
        assertThat(FinanceService.selectOutcome(OrderStatus.CANCELLED, CancelReason.EXPIRED, () -> false))
                .contains(PaymentOutcome.REFUNDED_ORDER_EXPIRED);
    }

    @Test
    void aCancelledOrderIsRefundedAndNeverReinstated() {
        for (CancelReason reason : Arrays.asList(CancelReason.CUSTOMER, CancelReason.STAFF, null)) { // null = legacy
            assertThat(FinanceService.selectOutcome(OrderStatus.CANCELLED, reason, MUST_NOT_REINSTATE))
                    .contains(PaymentOutcome.REFUNDED_ORDER_CANCELLED);
        }
    }

    @Test
    void paidShippedOrDeliveredOrdersRejectThePayment() {
        for (OrderStatus status : new OrderStatus[] {OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED}) {
            assertThat(FinanceService.selectOutcome(status, null, MUST_NOT_REINSTATE)).isEmpty();
        }
    }

    @Test
    void bothRefundOutcomesCountAsRefunded() {
        assertThat(PaymentOutcome.REFUNDED_ORDER_EXPIRED.refunded()).isTrue();
        assertThat(PaymentOutcome.REFUNDED_ORDER_CANCELLED.refunded()).isTrue();
        assertThat(PaymentOutcome.APPLIED.refunded()).isFalse();
        assertThat(PaymentOutcome.APPLIED_AFTER_REINSTATEMENT.refunded()).isFalse();
    }
}
