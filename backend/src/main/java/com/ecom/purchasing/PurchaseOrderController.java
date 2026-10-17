package com.ecom.purchasing;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ecom.common.web.PageResponse;
import com.ecom.purchasing.PurchasingDtos.DraftsFromSuggestionsResponse;
import com.ecom.purchasing.PurchasingDtos.GoodsReceiptRequest;
import com.ecom.purchasing.PurchasingDtos.GoodsReceiptResponse;
import com.ecom.purchasing.PurchasingDtos.PurchaseOrderRequest;
import com.ecom.purchasing.PurchasingDtos.PurchaseOrderResponse;
import com.ecom.security.SodOverrideRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/purchasing/purchase-orders")
@Tag(name = "Purchasing")
public class PurchaseOrderController {

    private final PurchasingService purchasingService;

    public PurchaseOrderController(PurchasingService purchasingService) {
        this.purchasingService = purchasingService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_purchasing:read')")
    @Operation(operationId = "listPurchaseOrders", summary = "List purchase orders by status and/or supplier, newest first")
    public PageResponse<PurchaseOrderResponse> list(@RequestParam(required = false) PurchaseOrderStatus status,
                                                    @RequestParam(required = false) UUID supplierId,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return purchasingService.list(status, supplierId, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_purchasing:read')")
    @Operation(operationId = "getPurchaseOrder", summary = "Get a purchase order with ordered/received quantities")
    public PurchaseOrderResponse get(@PathVariable UUID id) {
        return purchasingService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_purchasing:write')")
    @Operation(operationId = "createPurchaseOrder",
            summary = "Create a draft purchase order. Requires Idempotency-Key; a retry returns the original (200, Idempotent-Replayed: true).")
    public ResponseEntity<PurchaseOrderResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                        @Valid @RequestBody PurchaseOrderRequest request) {
        PurchasingService.CreateResult result = purchasingService.create(idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.purchaseOrder());
        }
        return ResponseEntity.created(URI.create("/api/v1/purchasing/purchase-orders/" + result.purchaseOrder().id()))
                .body(result.purchaseOrder());
    }

    @PostMapping("/from-reorder-suggestions")
    @PreAuthorize("hasAuthority('SCOPE_purchasing:write')")
    @Operation(operationId = "createPurchaseOrdersFromReorderSuggestions",
            summary = "Create one draft purchase order per preferred supplier from current reorder suggestions")
    public DraftsFromSuggestionsResponse fromSuggestions() {
        return purchasingService.createDraftsFromReorderSuggestions();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_purchasing:write')")
    @Operation(operationId = "updateDraftPurchaseOrder", summary = "Replace supplier, date, notes and lines of a draft")
    public PurchaseOrderResponse update(@PathVariable UUID id, @Valid @RequestBody PurchaseOrderRequest request) {
        return purchasingService.updateDraft(id, request);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('SCOPE_purchasing:write')")
    @Operation(operationId = "submitPurchaseOrder", summary = "Submit a draft for approval")
    public PurchaseOrderResponse submit(@PathVariable UUID id) {
        return purchasingService.submit(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('SCOPE_purchasing:approve')")
    @Operation(operationId = "approvePurchaseOrder",
            summary = "Approve a submitted purchase order. Strict mode: must be a different user from its creator. "
                    + "Small-team mode: the creator may approve with a sodOverrideReason (audited as SOD_OVERRIDDEN).")
    public PurchaseOrderResponse approve(@PathVariable UUID id,
                                         @Valid @RequestBody(required = false) SodOverrideRequest body) {
        return purchasingService.approve(id, body == null ? null : body.sodOverrideReason());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_purchasing:write')")
    @Operation(operationId = "cancelPurchaseOrder", summary = "Cancel a purchase order that has not received anything")
    public PurchaseOrderResponse cancel(@PathVariable UUID id) {
        return purchasingService.cancel(id);
    }

    @PostMapping("/{id}/receipts")
    @PreAuthorize("hasAuthority('SCOPE_inventory:write')")
    @Operation(operationId = "receivePurchaseOrder",
            summary = "Book a (partial) delivery: adds stock at PO cost and posts to the ledger. The receiver must differ from "
                    + "the PO creator and approver. Requires Idempotency-Key; "
                    + "a retry with the same key returns the original receipt (200, Idempotent-Replayed: true).")
    public ResponseEntity<GoodsReceiptResponse> receive(@PathVariable UUID id,
                                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                        @Valid @RequestBody GoodsReceiptRequest request) {
        PurchasingService.ReceiptResult result = purchasingService.receive(id, idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.receipt());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.receipt());
    }

    @GetMapping("/{id}/receipts")
    @PreAuthorize("hasAuthority('SCOPE_purchasing:read')")
    @Operation(operationId = "listGoodsReceipts", summary = "List deliveries booked against a purchase order")
    public List<GoodsReceiptResponse> receipts(@PathVariable UUID id) {
        return purchasingService.listReceipts(id);
    }
}
