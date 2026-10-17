package com.ecom.inventory;

import java.util.UUID;

/**
 * A stock movement that changes inventory VALUE outside a purchase/sale (opening balance, count adjustment).
 * Finance listens and posts the journal entry in the same transaction.
 */
public record StockValuationEvent(UUID movementId, Kind kind, String description, long valueMinor) {

    public enum Kind { OPENING, ADJUSTMENT_GAIN, ADJUSTMENT_LOSS }
}
