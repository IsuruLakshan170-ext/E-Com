package com.ecom.order;

/** Why an order was cancelled. Only EXPIRED orders accept a late payment (R3). */
public enum CancelReason {
    CUSTOMER,
    STAFF,
    EXPIRED
}
