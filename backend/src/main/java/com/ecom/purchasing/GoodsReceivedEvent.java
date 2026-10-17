package com.ecom.purchasing;

import java.util.UUID;

/** Published in the receiving transaction; finance posts Dr Inventory / Cr Accounts payable. */
public record GoodsReceivedEvent(UUID receiptId, String description, long totalCostMinor) {
}
