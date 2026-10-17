package com.ecom.catalog;

import java.time.Instant;
import java.util.UUID;

import com.ecom.common.web.Money;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {

    private static final String SKU_REGEX = "^[A-Z0-9][A-Z0-9_-]{2,63}$";
    private static final String SKU_MESSAGE = "must be 3-64 chars of A-Z, 0-9, '-' or '_'";
    /** Same-origin path only (both CSPs allow img-src 'self'); mirrors V8's chk_products_image_url. */
    private static final String IMAGE_URL_REGEX = "^/(?!/)(?!.*\\.\\.)[A-Za-z0-9_./-]{1,299}$";
    private static final String IMAGE_URL_MESSAGE = "must be a same-origin path like /products/yoga-mat.jpg";

    private CatalogDtos() {
    }

    public record CategoryRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 1000) String description) {
    }

    public record CategoryResponse(UUID id, String name, String slug, String description) {
        static CategoryResponse from(Category c) {
            return new CategoryResponse(c.getId(), c.getName(), c.getSlug(), c.getDescription());
        }
    }

    /** initialStock + unitCostMinor create the opening stock balance (stock ledger + general ledger). */
    public record CreateProductRequest(
            @NotBlank @Pattern(regexp = SKU_REGEX, message = SKU_MESSAGE) String sku,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 4000) String description,
            @Pattern(regexp = IMAGE_URL_REGEX, message = IMAGE_URL_MESSAGE) String imageUrl,
            @NotNull @PositiveOrZero @Max(1_000_000_000L) Long priceMinor,
            @PositiveOrZero @Max(1_000_000) Integer initialStock,
            @PositiveOrZero @Max(1_000_000_000L) Long unitCostMinor,
            UUID categoryId,
            Boolean active) {
    }

    /** No stock or cost here: stock changes only via inventory adjustments, receipts, sales and returns. */
    public record UpdateProductRequest(
            @NotBlank @Pattern(regexp = SKU_REGEX, message = SKU_MESSAGE) String sku,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 4000) String description,
            @Pattern(regexp = IMAGE_URL_REGEX, message = IMAGE_URL_MESSAGE) String imageUrl,
            @NotNull @PositiveOrZero @Max(1_000_000_000L) Long priceMinor,
            UUID categoryId,
            Boolean active) {
    }

    /** Public catalog view: deliberately excludes cost and reorder data. availableQuantity excludes reserved units. */
    public record ProductResponse(UUID id, String sku, String name, String description, String imageUrl, Money price,
                                  int availableQuantity, boolean inStock, UUID categoryId, boolean active,
                                  Instant createdAt, Instant updatedAt) {
        static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getImageUrl(),
                    Money.of(p.getPriceMinor(), p.getCurrency()), p.getAvailable(), p.getAvailable() > 0,
                    p.getCategoryId(), p.isActive(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }
}
