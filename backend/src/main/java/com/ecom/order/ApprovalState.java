package com.ecom.order;

/**
 * Human-in-the-loop approval for an order an agent placed above its key's threshold
 * (backend/REVIEW.md §5.3). A null column means no approval was ever required — the normal case,
 * and how every order placed by a human looks.
 */
public enum ApprovalState {
    /** Placed, stock reserved, but payment is blocked until the owner approves. */
    PENDING,
    APPROVED,
    /** The owner said no (or the order was cancelled by the customer/staff while still PENDING). */
    REJECTED,
    /**
     * The order was still PENDING when its payment window ran out (review round 2, A1). Without this, a
     * cancelled-by-expiry order kept approvalState = PENDING forever: it stayed in the owner's approval
     * queue, {@code approveAgentOrder} would still accept it, and {@code recordPayment} would refuse a late
     * payment with "waiting for approval" instead of following the R3/R11 late-payment rules.
     */
    EXPIRED
}
