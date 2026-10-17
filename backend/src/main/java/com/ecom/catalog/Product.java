package com.ecom.catalog;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 4000)
    private String description;

    /** Same-origin path to the product photo (e.g. /products/yoga-mat.jpg), or null. */
    @Column(name = "image_url", length = 300)
    private String imageUrl;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    /** On hand: physically in the warehouse. */
    @Column(nullable = false)
    private int stock;

    /** Promised to unshipped orders. available = stock - reserved. */
    @Column(nullable = false)
    private int reserved;

    /**
     * Average unit cost = stock value / on hand, rounded (display, reorder estimates, and the value of
     * count gains). Internal: never exposed through the public catalog.
     */
    @Column(name = "cost_minor", nullable = false)
    private long costMinor;

    /**
     * R4: exact value of the units on hand. Every movement changes it by exactly the amount posted to
     * GL 1200 Inventory, so the ledger and the stock records can never drift apart.
     */
    @Column(name = "stock_value_minor", nullable = false)
    private long stockValueMinor;

    @Column(name = "reorder_point", nullable = false)
    private int reorderPoint;

    @Column(name = "reorder_quantity", nullable = false)
    private int reorderQuantity;

    @Column(name = "preferred_supplier_id")
    private UUID preferredSupplierId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "category_id")
    private UUID categoryId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Product() {
    }

    /** New products start with zero stock; opening stock goes through InventoryService. */
    Product(String sku, String name, String description, String imageUrl, long priceMinor, String currency,
            UUID categoryId, boolean active) {
        this.currency = currency;
        update(sku, name, description, imageUrl, priceMinor, categoryId, active);
    }

    void update(String sku, String name, String description, String imageUrl, long priceMinor, UUID categoryId,
                boolean active) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.imageUrl = imageUrl;
        this.priceMinor = priceMinor;
        this.categoryId = categoryId;
        this.active = active;
    }

    void initCost(long costMinor) {
        if (stock != 0) {
            throw new IllegalStateException("Cost can only be initialised while stock is zero");
        }
        this.costMinor = costMinor;
    }

    void deactivate() {
        this.active = false;
    }

    // ---- stock: call ONLY from InventoryService, under a row lock ----
    // Methods that move units return the VALUE moved, which the caller records on the StockMovement
    // and posts to the ledger. Outbound units take their proportional share of the value pool; the
    // last unit out takes whatever is left, so value reaches exactly 0 with the stock.

    public int getAvailable() {
        return stock - reserved;
    }

    /** Checkout: promise units to an order without moving them. */
    public void reserve(int quantity) {
        if (quantity <= 0 || quantity > getAvailable()) {
            throw new IllegalStateException("Invalid reservation");
        }
        reserved += quantity;
    }

    /** Cancel / expiry of an unshipped order. */
    public void releaseReservation(int quantity) {
        if (quantity <= 0 || quantity > reserved) {
            throw new IllegalStateException("Invalid reservation release");
        }
        reserved -= quantity;
    }

    /** Shipment: reserved units physically leave the warehouse. @return value removed (= COGS) */
    public long consumeReservation(int quantity) {
        if (quantity <= 0 || quantity > reserved || quantity > stock) {
            throw new IllegalStateException("Invalid reservation consumption");
        }
        long value = takeValue(quantity);
        reserved -= quantity;
        stock -= quantity;
        refreshAverage();
        return value;
    }

    /** Outbound movement that is not a sale (e.g. damage). Never eats reserved units. @return value removed */
    public long decreaseStock(int quantity) {
        if (quantity <= 0 || quantity > getAvailable()) {
            throw new IllegalStateException("Invalid stock decrease");
        }
        long value = takeValue(quantity);
        stock -= quantity;
        refreshAverage();
        return value;
    }

    /** Inbound units without a cost of their own (count gain), valued at the current average. @return value added */
    public long increaseStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalStateException("Invalid stock increase");
        }
        return receiveValue(quantity, Math.multiplyExact((long) quantity, costMinor));
    }

    /** Inbound units at a known unit cost (purchase receipt, opening stock). @return value added */
    public long receiveAtCost(int quantity, long unitCostMinor) {
        if (quantity <= 0 || unitCostMinor < 0) {
            throw new IllegalStateException("Invalid receipt");
        }
        return receiveValue(quantity, Math.multiplyExact((long) quantity, unitCostMinor));
    }

    /** Inbound units at a known total value (customer return at its share of the shipped cost). @return value added */
    public long receiveValue(int quantity, long valueMinor) {
        if (quantity <= 0 || valueMinor < 0) {
            throw new IllegalStateException("Invalid inbound value");
        }
        stock = Math.addExact(stock, quantity);
        stockValueMinor = Math.addExact(stockValueMinor, valueMinor);
        refreshAverage();
        return valueMinor;
    }

    /** Proportional share of the value pool, rounded half-up; the last units take the remainder. */
    private long takeValue(int quantity) {
        long value = quantity == stock
                ? stockValueMinor
                : (Math.multiplyExact(stockValueMinor, (long) quantity) + stock / 2) / stock;
        stockValueMinor -= value;
        return value;
    }

    /** Keeps the last known average when stock reaches zero (used to value a later count gain). */
    private void refreshAverage() {
        if (stock > 0) {
            costMinor = (stockValueMinor + stock / 2) / stock;
        }
    }

    public void setReorderPolicy(int reorderPoint, int reorderQuantity, UUID preferredSupplierId) {
        this.reorderPoint = reorderPoint;
        this.reorderQuantity = reorderQuantity;
        this.preferredSupplierId = preferredSupplierId;
    }

    public UUID getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getImageUrl() { return imageUrl; }
    public long getPriceMinor() { return priceMinor; }
    public String getCurrency() { return currency; }
    public int getStock() { return stock; }
    public int getReserved() { return reserved; }
    public long getCostMinor() { return costMinor; }
    public long getStockValueMinor() { return stockValueMinor; }
    public int getReorderPoint() { return reorderPoint; }
    public int getReorderQuantity() { return reorderQuantity; }
    public UUID getPreferredSupplierId() { return preferredSupplierId; }
    public boolean isActive() { return active; }
    public UUID getCategoryId() { return categoryId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
