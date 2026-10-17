package com.ecom.inventory;

import java.time.Instant;
import java.util.UUID;

import com.ecom.common.web.Money;
import com.ecom.common.web.PageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    /** A reason is mandatory: unexplained stock changes are how shrinkage and fraud hide. */
    public record StockAdjustmentRequest(
            @NotNull @Min(-1_000_000) @Max(1_000_000) Integer delta,
            @NotBlank @Size(max = 200) String reason) {
    }

    /** Back-office view of a product. Unlike the public catalog, this includes cost and reservations. */
    public record InventoryItemResponse(UUID productId, String sku, String name, boolean active, int onHand,
                                        int reserved, int available, Money unitCost, Money stockValue,
                                        int reorderPoint, int reorderQuantity, UUID preferredSupplierId) {
    }

    /**
     * value: exact signed value moved (null for rows recorded before value tracking). sku + productName (BE-3)
     * spare clients a lookup per row and work for hidden products too.
     */
    public record StockMovementResponse(UUID id, UUID productId, String sku, String productName, MovementType type,
                                        int quantity, Money unitCost, Money value, int stockAfter,
                                        String referenceType, UUID referenceId, String note, UUID actorId,
                                        Instant createdAt) {
    }

    public record InventoryValuationResponse(PageResponse<InventoryItemResponse> items, long totalUnits,
                                             Money totalValue, Instant asOf) {
    }
}
