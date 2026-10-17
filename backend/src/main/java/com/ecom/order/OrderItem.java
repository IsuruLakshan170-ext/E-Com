package com.ecom.order;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Immutable snapshot of what was bought, at what price and at what cost; later catalog edits never change it. */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private CustomerOrder order;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    @Column(name = "unit_price_minor", nullable = false, updatable = false)
    private long unitPriceMinor;

    /** Average unit cost at checkout: a margin estimate only. Never exposed to customers. */
    @Column(name = "unit_cost_minor", nullable = false, updatable = false)
    private long unitCostMinor;

    /**
     * R4: the exact inventory value that left the warehouse for this line at SHIPMENT. This is the COGS
     * finance posts, and the basis a return is restocked at. Null until shipped.
     */
    @Column(name = "shipped_cost_minor")
    private Long shippedCostMinor;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "line_total_minor", nullable = false, updatable = false)
    private long lineTotalMinor;

    protected OrderItem() {
    }

    OrderItem(CustomerOrder order, UUID productId, String sku, String productName, long unitPriceMinor,
              long unitCostMinor, int quantity) {
        this.order = order;
        this.productId = productId;
        this.sku = sku;
        this.productName = productName;
        this.unitPriceMinor = unitPriceMinor;
        this.unitCostMinor = unitCostMinor;
        this.quantity = quantity;
        this.lineTotalMinor = Math.multiplyExact(unitPriceMinor, quantity);
    }

    void recordShipmentCost(long costMinor) {
        if (shippedCostMinor != null) {
            throw new IllegalStateException("Shipment cost already recorded");
        }
        this.shippedCostMinor = costMinor;
    }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getProductName() { return productName; }
    public long getUnitPriceMinor() { return unitPriceMinor; }
    public long getUnitCostMinor() { return unitCostMinor; }
    public Long getShippedCostMinor() { return shippedCostMinor; }
    public int getQuantity() { return quantity; }
    public long getLineTotalMinor() { return lineTotalMinor; }
}
