package com.ecom.cart;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecom.common.web.Money;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class CartDtos {

    private CartDtos() {
    }

    public record AddCartItemRequest(@NotNull UUID productId, @NotNull @Min(1) @Max(100) Integer quantity) {
    }

    /** quantity 0 removes the line. */
    public record UpdateCartItemRequest(@NotNull @Min(0) @Max(100) Integer quantity) {
    }

    /** issue is null when the line can be checked out, otherwise PRODUCT_UNAVAILABLE or INSUFFICIENT_STOCK. */
    public record CartLine(UUID productId, String sku, String name, Money unitPrice, int quantity,
                           Money lineTotal, Integer availableQuantity, String issue) {
    }

    /** Prices are always the CURRENT catalog price; the final price is fixed at checkout. */
    public record CartResponse(List<CartLine> items, int totalQuantity, Money subtotal,
                               boolean readyForCheckout, Instant updatedAt) {
    }

    /**
     * Totals the cart would have at checkout for a destination country (prices are tax-exclusive).
     * Pass total.amountMinor to placeOrder as expectedTotalMinor: that is the "quote, then confirm" step.
     */
    public record CartQuoteResponse(String countryCode, Money subtotal, int taxRateBasisPoints, Money tax,
                                    Money total, boolean readyForCheckout) {
    }
}
