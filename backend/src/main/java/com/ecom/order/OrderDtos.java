package com.ecom.order;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ecom.common.web.Money;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record ShippingAddressDto(
            @NotBlank @Size(max = 100) String recipientName,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @Size(max = 100) String region,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 code, e.g. US") String countryCode) {

        ShippingAddress toEntity() {
            return new ShippingAddress(recipientName.trim(), line1.trim(), line2, city.trim(), region,
                    postalCode.trim(), countryCode);
        }


        public static ShippingAddressDto from(ShippingAddress a) {
            return new ShippingAddressDto(a.getRecipientName(), a.getLine1(), a.getLine2(), a.getCity(),
                    a.getRegion(), a.getPostalCode(), a.getCountryCode());
        }
    }

    /**
     * Checkout always prices from the server-side cart; the client never sends prices. expectedTotalMinor is
     * the total the buyer was shown (from quoteCart): if anything changed since, checkout fails with
     * PRICE_CHANGED instead of charging a different amount (M1). It is also part of the idempotency fingerprint.
     */
    public record CheckoutRequest(@NotNull @Valid ShippingAddressDto shippingAddress,
                                  @NotNull @PositiveOrZero Long expectedTotalMinor) {
    }

    public record UpdateOrderStatusRequest(@NotNull OrderStatus status) {
    }

    public record OrderLine(UUID productId, String sku, String productName, Money unitPrice, int quantity,
                            Money lineTotal) {
        public static OrderLine from(OrderItem i, String currency) {
            return new OrderLine(i.getProductId(), i.getSku(), i.getProductName(),
                    Money.of(i.getUnitPriceMinor(), currency), i.getQuantity(), Money.of(i.getLineTotalMinor(), currency));
        }
    }

    /** allowedActions tells clients (and agents) what the customer may do next instead of making them guess. */
    public record OrderResponse(UUID id, String orderNumber, OrderStatus status, CancelReason cancelReason,
                                List<OrderLine> items,
                                Money subtotal, int taxRateBasisPoints, Money tax, Money total, Money refunded,
                                ShippingAddressDto shippingAddress, List<String> allowedActions,
                                Set<OrderStatus> allowedNextStatuses, Instant expiresAt, Instant paidAt, Instant shippedAt,
                                Instant deliveredAt, Instant createdAt, Instant updatedAt,
                                /* Agent guardrails: null unless an API key placed this order / it needs approval. */
                                UUID placedByApiKeyId, ApprovalState approvalState, Instant approvalDecidedAt) {
    }
}
