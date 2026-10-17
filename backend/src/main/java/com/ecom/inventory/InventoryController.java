package com.ecom.inventory;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.common.web.PageResponse;
import com.ecom.inventory.InventoryDtos.InventoryItemResponse;
import com.ecom.inventory.InventoryDtos.InventoryValuationResponse;
import com.ecom.inventory.InventoryDtos.StockAdjustmentRequest;
import com.ecom.inventory.InventoryDtos.StockMovementResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Reorder policy and suggestions live in purchasing (ReplenishmentController) under the same /inventory paths. */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/products/{productId}")
    @PreAuthorize("hasAuthority('SCOPE_inventory:read')")
    @Operation(operationId = "getInventoryItem",
            summary = "On hand, reserved and available quantity, unit cost, value and reorder policy of a product")
    public InventoryItemResponse get(@PathVariable UUID productId) {
        return inventoryService.getItem(productId);
    }

    @PostMapping("/products/{productId}/adjustments")
    @PreAuthorize("hasAuthority('SCOPE_inventory:write')")
    @Operation(operationId = "adjustStock",
            summary = "Correct on-hand stock (positive or negative delta) with a mandatory reason; posted to the ledger. "
                    + "Cannot remove units reserved for orders. Requires Idempotency-Key; a retry with the same key "
                    + "returns the current item (200, Idempotent-Replayed: true) and never adjusts twice.")
    public ResponseEntity<InventoryItemResponse> adjust(@PathVariable UUID productId,
                                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                        @Valid @RequestBody StockAdjustmentRequest request) {
        InventoryService.AdjustResult result = inventoryService.adjust(productId, idempotencyKey, request);
        return result.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.item())
                : ResponseEntity.ok(result.item());
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('SCOPE_inventory:read')")
    @Operation(operationId = "listStockMovements", summary = "Stock ledger: every on-hand change with reason and reference")
    public PageResponse<StockMovementResponse> movements(@RequestParam(required = false) UUID productId,
                                                         @RequestParam(required = false) MovementType type,
                                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                                         @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return inventoryService.listMovements(productId, type, page, size);
    }

    @GetMapping("/valuation")
    @PreAuthorize("hasAuthority('SCOPE_inventory:read')")
    @Operation(operationId = "getInventoryValuation", summary = "Value of stock on hand at moving-average cost (paged items, full totals)")
    public InventoryValuationResponse valuation(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        return inventoryService.valuation(page, size);
    }
}
