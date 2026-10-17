package com.ecom.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.finance.FinanceDtos.PaymentResponse;
import com.ecom.finance.PaymentOutcome;
import com.ecom.order.CancelReason;
import com.ecom.order.CustomerOrder;
import com.ecom.order.OrderStatus;
import com.ecom.user.Role;
import com.ecom.user.User;

/**
 * R11 (Isuru's decision): money that arrives for an order cancelled by the customer or staff is always booked and
 * refunded in full in the same transaction. The order stays CANCELLED and its stock is never re-reserved.
 */
class CancelledOrderPaymentIT extends IntegrationTestBase {

    @Test
    void aPaymentForACustomerCancelledOrderIsRecordedAndRefunded() {
        User admin = user(Role.ADMIN);
        User financeUser = user(Role.FINANCE_MANAGER);
        UUID product = product(admin, 5, 100, 400);
        User buyer = user(Role.CUSTOMER);
        UUID orderId = placeOrder(buyer, product, 2).id();
        as(buyer, () -> orders.cancelForUser(buyer.getId(), orderId));
        assertThat(reserved(product)).isZero();

        PaymentResponse payment = pay(financeUser, orderId);

        assertRefundedAndStillCancelled(payment, orderId, CancelReason.CUSTOMER);
        assertThat(reserved(product)).isZero(); // never re-reserved
    }

    @Test
    void aPaymentForAStaffCancelledUnpaidOrderIsRecordedAndRefunded() {
        User admin = user(Role.ADMIN);
        User financeUser = user(Role.FINANCE_MANAGER);
        User service = user(Role.CUSTOMER_SERVICE);
        UUID product = product(admin, 5, 100, 400);
        UUID orderId = placeOrder(user(Role.CUSTOMER), product, 1).id();
        as(service, () -> orders.updateStatus(orderId, OrderStatus.CANCELLED));

        PaymentResponse payment = pay(financeUser, orderId);

        assertRefundedAndStillCancelled(payment, orderId, CancelReason.STAFF);
    }

    @Test
    void legacyCancelledOrdersWithoutAReasonAreRefundedToo() {
        User admin = user(Role.ADMIN);
        User financeUser = user(Role.FINANCE_MANAGER);
        UUID product = product(admin, 5, 100, 400);
        User buyer = user(Role.CUSTOMER);
        UUID orderId = placeOrder(buyer, product, 1).id();
        as(buyer, () -> orders.cancelForUser(buyer.getId(), orderId));
        jdbc.update("update orders set cancel_reason = null where id = ?", orderId); // as cancelled before V4

        PaymentResponse payment = pay(financeUser, orderId);

        assertRefundedAndStillCancelled(payment, orderId, null);
    }

    @Test
    void anOrderThatWasPaidAndThenCancelledRejectsASecondPayment() {
        User admin = user(Role.ADMIN);
        User financeUser = user(Role.FINANCE_MANAGER);
        User service = user(Role.CUSTOMER_SERVICE);
        UUID product = product(admin, 5, 100, 400);
        UUID orderId = placeOrder(user(Role.CUSTOMER), product, 1).id();
        assertThat(pay(financeUser, orderId).outcome()).isEqualTo(PaymentOutcome.APPLIED);
        as(service, () -> orders.updateStatus(orderId, OrderStatus.CANCELLED)); // refunded at cancellation

        // one payment per order: a second payment is a duplicate, not an R11 case
        assertThatThrownBy(() -> pay(financeUser, orderId)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    }

    private void assertRefundedAndStillCancelled(PaymentResponse payment, UUID orderId, CancelReason expectedReason) {
        assertThat(payment.outcome()).isEqualTo(PaymentOutcome.REFUNDED_ORDER_CANCELLED);
        assertThat(payment.refundId()).isNotNull();
        assertThat(payment.creditNoteNumber()).startsWith("CN-");
        assertThat(payment.invoiceId()).isNull();
        CustomerOrder order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo(expectedReason);
        assertThat(order.getRefundedMinor()).isEqualTo(order.getTotalMinor());
        assertThat(auditCount("ORDER_CANCELLED_PAYMENT_REFUNDED", orderId)).isEqualTo(1);
        assertThat(cashNet(payment.id(), payment.refundId())).isZero(); // in and straight back out
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }
}
