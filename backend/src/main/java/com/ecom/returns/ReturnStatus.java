package com.ecom.returns;

import java.util.EnumSet;
import java.util.Set;

/** REQUESTED -> APPROVED -> RECEIVED -> REFUNDED, or REQUESTED -> REJECTED. */
public enum ReturnStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    RECEIVED,
    REFUNDED;

    public Set<ReturnStatus> allowedNext() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(APPROVED, REJECTED);
            case APPROVED -> EnumSet.of(RECEIVED);
            case RECEIVED -> EnumSet.of(REFUNDED);
            case REJECTED, REFUNDED -> EnumSet.noneOf(ReturnStatus.class);
        };
    }
}
