package com.ecom.purchasing;

import java.util.UUID;

record PoLineSpec(UUID productId, int quantity, long unitCostMinor) {
}
