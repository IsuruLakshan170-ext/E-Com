package com.ecom.purchasing;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.common.web.PageResponse;
import com.ecom.inventory.InventoryDtos.InventoryItemResponse;
import com.ecom.purchasing.PurchasingDtos.ReorderPolicyRequest;
import com.ecom.purchasing.PurchasingDtos.ReorderSuggestion;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Same URLs and operationIds as before the move from the inventory package. */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory")
public class ReplenishmentController {

    private final ReplenishmentService replenishment;

    public ReplenishmentController(ReplenishmentService replenishment) {
        this.replenishment = replenishment;
    }

    @PutMapping("/products/{productId}/reorder-policy")
    @PreAuthorize("hasAuthority('SCOPE_inventory:write')")
    @Operation(operationId = "setReorderPolicy", summary = "Set reorder point, reorder quantity and preferred supplier")
    public InventoryItemResponse setReorderPolicy(@PathVariable UUID productId,
                                                  @Valid @RequestBody ReorderPolicyRequest request) {
        return replenishment.setReorderPolicy(productId, request);
    }

    @GetMapping("/reorder-suggestions")
    @PreAuthorize("hasAuthority('SCOPE_inventory:read')")
    @Operation(operationId = "listReorderSuggestions",
            summary = "Products whose available quantity plus open purchase orders is at or below their reorder point")
    public PageResponse<ReorderSuggestion> suggestions(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                       @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return replenishment.suggestions(page, size);
    }
}
