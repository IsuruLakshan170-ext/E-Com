package com.ecom.order;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle. PAID is reached only through finance's recordPayment (or automatically for zero-total
 * orders); a payment-provider webhook will call the same service. Unpaid orders expire after the
 * configured payment timeout and release their reserved stock.
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PENDING_PAYMENT -> EnumSet.of(PAID, CANCELLED);
            case PAID -> EnumSet.of(SHIPPED, CANCELLED);
            case SHIPPED -> EnumSet.of(DELIVERED);
            case DELIVERED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    /** Customers can only cancel before payment; after that it needs a refund (phase 2). */
    public boolean customerCancellable() {
        return this == PENDING_PAYMENT;
    }
}
