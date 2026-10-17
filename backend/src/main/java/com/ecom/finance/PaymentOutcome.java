package com.ecom.finance;

/** R3/R11: what recording a payment did. */
public enum PaymentOutcome {
    /** Normal case: the order was awaiting payment and is now PAID. */
    APPLIED,
    /** The order had expired, its stock could be re-reserved, and it is PAID again. */
    APPLIED_AFTER_REINSTATEMENT,
    /** The order had expired and its stock was gone: the payment was recorded and refunded in full. */
    REFUNDED_ORDER_EXPIRED,
    /** R11: the order was cancelled by the customer or staff: the payment was recorded and refunded in full. */
    REFUNDED_ORDER_CANCELLED;

    public boolean refunded() {
        return this == REFUNDED_ORDER_EXPIRED || this == REFUNDED_ORDER_CANCELLED;
    }
}
