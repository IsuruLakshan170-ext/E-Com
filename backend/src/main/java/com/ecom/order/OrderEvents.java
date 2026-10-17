package com.ecom.order;

import java.util.UUID;

/**
 * Domain events published inside the order's transaction (M12). Finance listens synchronously, so the
 * accounting entry still commits or rolls back with the order - but the order module no longer calls finance.
 */
public final class OrderEvents {

    private OrderEvents() {
    }

    /** Reserved stock left the warehouse: finance recognises revenue and posts COGS. */
    public record OrderShipped(UUID orderId) {
    }

    /** A PAID order was cancelled before shipping: finance refunds it in full. */
    public record PaidOrderCancelled(UUID orderId) {
    }

    /** A zero-total order was settled at checkout without a payment: finance issues the (zero) invoice. */
    public record ZeroTotalOrderSettled(UUID orderId) {
    }
}
