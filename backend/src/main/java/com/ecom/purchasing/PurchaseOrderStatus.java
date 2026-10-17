package com.ecom.purchasing;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public enum PurchaseOrderStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CANCELLED;

    public Set<PurchaseOrderStatus> allowedNext() {
        return switch (this) {
            case DRAFT -> EnumSet.of(SUBMITTED, CANCELLED);
            case SUBMITTED -> EnumSet.of(APPROVED, CANCELLED);
            case APPROVED -> EnumSet.of(PARTIALLY_RECEIVED, RECEIVED, CANCELLED);
            case PARTIALLY_RECEIVED -> EnumSet.of(PARTIALLY_RECEIVED, RECEIVED);
            case RECEIVED, CANCELLED -> EnumSet.noneOf(PurchaseOrderStatus.class);
        };
    }

    public boolean receivable() {
        return this == APPROVED || this == PARTIALLY_RECEIVED;
    }

    /** What a client may do next; mirrors the endpoints. */
    public List<String> allowedActions() {
        return switch (this) {
            case DRAFT -> List.of("update", "submit", "cancel");
            case SUBMITTED -> List.of("approve", "cancel");
            case APPROVED -> List.of("receive", "cancel");
            case PARTIALLY_RECEIVED -> List.of("receive");
            case RECEIVED, CANCELLED -> List.of();
        };
    }
}
