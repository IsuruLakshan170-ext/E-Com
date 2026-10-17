package com.ecom.purchasing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ecom.common.web.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class PurchasingDtos {

    private PurchasingDtos() {
    }

    public record SupplierRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{1,31}$", message = "must be 2-32 chars of A-Z, 0-9, '-' or '_'") String code,
            @NotBlank @Size(max = 200) String name,
            @Email @Size(max = 254) String email,
            @Size(max = 40) String phone,
            @Size(max = 500) String address,
            @NotNull @Min(0) @Max(365) Integer paymentTermsDays,
            Boolean active) {
    }

    public record SupplierResponse(UUID id, String code, String name, String email, String phone, String address,
                                   int paymentTermsDays, boolean active, Instant createdAt) {
        static SupplierResponse from(Supplier s) {
            return new SupplierResponse(s.getId(), s.getCode(), s.getName(), s.getEmail(), s.getPhone(),
                    s.getAddress(), s.getPaymentTermsDays(), s.isActive(), s.getCreatedAt());
        }
    }

    public record PurchaseOrderLineRequest(
            @NotNull UUID productId,
            @NotNull @Min(1) @Max(1_000_000) Integer quantity,
            @NotNull @PositiveOrZero @Max(1_000_000_000L) Long unitCostMinor) {
    }

    public record PurchaseOrderRequest(
            @NotNull UUID supplierId,
            @FutureOrPresent LocalDate expectedDate,
            @Size(max = 1000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull PurchaseOrderLineRequest> lines) {
    }

    /** BE-3: sku + productName so clients never look products up one by one (inactive products included). */
    public record PurchaseOrderLineResponse(int lineNo, UUID productId, String sku, String productName,
                                            int quantityOrdered, int quantityReceived, int quantityOutstanding,
                                            Money unitCost, Money lineTotal) {
    }

    public record PurchaseOrderResponse(UUID id, String poNumber, UUID supplierId, String supplierName,
                                        PurchaseOrderStatus status,
                                        List<PurchaseOrderLineResponse> lines, Money total, LocalDate expectedDate,
                                        String notes, UUID createdBy, UUID approvedBy, Instant approvedAt,
                                        List<String> allowedActions, Instant createdAt, Instant updatedAt) {
    }

    public record GoodsReceiptLineRequest(
            @NotNull UUID productId,
            @NotNull @Min(1) @Max(1_000_000) Integer quantity) {
    }

    /** sodOverrideReason: only used in small-team mode when the receiver also raised/approved the PO (R10). */
    public record GoodsReceiptRequest(
            @NotEmpty @Size(max = 200) List<@Valid @NotNull GoodsReceiptLineRequest> lines,
            @Size(max = 500) String note,
            @Size(max = 300) String sodOverrideReason) {
    }

    public record GoodsReceiptLineResponse(UUID productId, String sku, String productName, int quantity,
                                           Money unitCost) {
    }

    public record GoodsReceiptResponse(UUID id, String receiptNumber, UUID purchaseOrderId,
                                       List<GoodsReceiptLineResponse> lines, Money totalCost, String note,
                                       UUID receivedBy, Instant receivedAt) {
    }

    /** L4: inactive suppliers are skipped and reported instead of failing the whole batch. */
    public record DraftsFromSuggestionsResponse(List<PurchaseOrderResponse> created,
                                                List<UUID> skippedProductIdsWithoutSupplier,
                                                List<UUID> skippedInactiveSupplierIds) {
    }

    public record ReorderPolicyRequest(
            @NotNull @Min(0) @Max(1_000_000) Integer reorderPoint,
            @NotNull @Min(0) @Max(1_000_000) Integer reorderQuantity,
            UUID preferredSupplierId) {
    }

    public record ReorderSuggestion(UUID productId, String sku, String name, int onHand, int reserved,
                                    int available, long onOrder, int reorderPoint, int suggestedQuantity,
                                    UUID preferredSupplierId, String preferredSupplierName, Money estimatedUnitCost) {
    }
}
