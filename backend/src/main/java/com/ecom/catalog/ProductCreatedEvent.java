package com.ecom.catalog;

import java.util.UUID;

/** Published in the creating transaction; inventory books the opening stock (keeps catalog free of inventory). */
public record ProductCreatedEvent(UUID productId, int initialStock, long unitCostMinor) {
}
