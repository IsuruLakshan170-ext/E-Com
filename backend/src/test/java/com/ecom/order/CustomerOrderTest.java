package com.ecom.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerOrderTest {

    private CustomerOrder order;
    private final Instant now = Instant.parse("2026-10-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        order = new CustomerOrder("ORD-1", UUID.randomUUID(), "USD",
                new ShippingAddress("A", "1 Main St", null, "Town", null, "123", "US"), now.plus(Duration.ofMinutes(30)));
        order.addItem(UUID.randomUUID(), "SKU-1", "Widget", 2_500, 1_000, 2); // 50.00
        order.applyTax(1_000);                                               // 10%
    }

    @Test
    void totalsIncludeTax() {
        assertThat(order.getSubtotalMinor()).isEqualTo(5_000);
        assertThat(order.getTaxMinor()).isEqualTo(500);
        assertThat(order.getTotalMinor()).isEqualTo(5_500);
    }

    @Test
    void paymentStampsPaidAtAndClearsExpiry() {
        order.transitionTo(OrderStatus.PAID, now);
        assertThat(order.getPaidAt()).isEqualTo(now);
        assertThat(order.getExpiresAt()).isNull();
    }

    @Test
    void cannotSkipLifecycleSteps() {
        assertThatThrownBy(() -> order.transitionTo(OrderStatus.SHIPPED, now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refundsCanNeverExceedWhatWasPaid() {
        order.recordRefund(5_000, 450);
        assertThat(order.refundableMinor()).isEqualTo(500);
        assertThatThrownBy(() -> order.recordRefund(501, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellingGoesThroughCancelAndRecordsTheReason() {
        assertThatThrownBy(() -> order.transitionTo(OrderStatus.CANCELLED, now)).isInstanceOf(IllegalArgumentException.class);
        order.cancel(CancelReason.EXPIRED, now);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.isExpired()).isTrue();
    }

    @Test
    void anExpiredOrderCanBeReinstatedAsPaid() {
        order.cancel(CancelReason.EXPIRED, now);
        order.reinstateAsPaid(now.plusSeconds(60));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getCancelReason()).isNull();
        assertThat(order.getPaidAt()).isEqualTo(now.plusSeconds(60));
        assertThat(order.getExpiresAt()).isNull();
    }

    @Test
    void onlyExpiredOrdersCanBeReinstated() {
        order.cancel(CancelReason.CUSTOMER, now);
        assertThatThrownBy(() -> order.reinstateAsPaid(now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void statusRules() {
        assertThat(OrderStatus.PENDING_PAYMENT.customerCancellable()).isTrue();
        assertThat(OrderStatus.PAID.customerCancellable()).isFalse();
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.DELIVERED.allowedNext()).isEmpty();
    }

    @Test
    void returnWindowIsThirtyDaysAfterDelivery() {
        assertThat(ReturnPolicy.withinWindow(now, now.plus(Duration.ofDays(30)))).isTrue();
        assertThat(ReturnPolicy.withinWindow(now, now.plus(Duration.ofDays(31)))).isFalse();
        assertThat(ReturnPolicy.withinWindow(null, now)).isFalse();
    }
}
