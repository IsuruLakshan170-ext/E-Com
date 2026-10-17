package com.ecom.inventory;

public enum MovementType {
    OPENING(true),
    ADJUSTMENT(false),
    SALE(false),
    SALE_CANCELLED(false),
    PURCHASE_RECEIPT(true),
    CUSTOMER_RETURN(true);

    private final boolean revaluesCost;

    MovementType(boolean revaluesCost) {
        this.revaluesCost = revaluesCost;
    }

    /** Inbound movements that carry their own unit cost and update the moving-average cost. */
    public boolean revaluesCost() {
        return revaluesCost;
    }
}
