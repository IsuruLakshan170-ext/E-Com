package com.ecom.order;

/**
 * R1: the one row-lock order used everywhere, so concurrent flows can't deadlock:
 * <pre>
 *   cart  ->  order  ->  return request  ->  products (always sorted by id, via lockAllByIds)
 * </pre>
 * <ul>
 *   <li>checkout: cart, then products (a brand-new order row conflicts with nobody);</li>
 *   <li>customer cancel, staff cancel, ship, expiry, recordPayment (incl. late-payment reinstatement):
 *       order, then products;</li>
 *   <li>return request: order; return receive: return, then products; return refund: order, then return.</li>
 * </ul>
 * Purchase-order receipts use their own document: purchase order, then products.
 * Documentation only - no code reads this class.
 */
final class LockOrder {
    private LockOrder() {
    }
}
