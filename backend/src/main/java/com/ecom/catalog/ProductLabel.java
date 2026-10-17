package com.ecom.catalog;

import java.util.UUID;

/**
 * BE-3: how a product is shown next to its id on documents that reference it (purchase orders, goods receipts,
 * stock movements). Any product, active or not: a hidden product still has history.
 */
public record ProductLabel(UUID id, String sku, String name) {

    /** Products are only ever soft-deleted and every reference has a foreign key, so this should never be needed. */
    public static ProductLabel missing(UUID id) {
        return new ProductLabel(id, "?", "Unknown product");
    }
}
