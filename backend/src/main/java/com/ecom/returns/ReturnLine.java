package com.ecom.returns;

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

@Entity
@Table(name = "return_lines")
public class ReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false, updatable = false)
    private ReturnRequest returnRequest;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price_minor", nullable = false, updatable = false)
    private long unitPriceMinor;

    @Column(name = "unit_cost_minor", nullable = false, updatable = false)
    private long unitCostMinor;

    /** R4: exact inventory value this line restocks: its share of the order line's shipped cost. */
    @Column(name = "restock_value_minor", nullable = false, updatable = false)
    private long restockValueMinor;

    protected ReturnLine() {
    }

    ReturnLine(ReturnRequest returnRequest, UUID productId, String sku, String productName, int quantity,
               long unitPriceMinor, long unitCostMinor, long restockValueMinor) {
        this.returnRequest = returnRequest;
        this.productId = productId;
        this.sku = sku;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPriceMinor = unitPriceMinor;
        this.unitCostMinor = unitCostMinor;
        this.restockValueMinor = restockValueMinor;
    }

    long lineTotalMinor() {
        return Math.multiplyExact(unitPriceMinor, (long) quantity);
    }

    long lineCostMinor() {
        return restockValueMinor;
    }

    public UUID getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public long getUnitPriceMinor() { return unitPriceMinor; }
    public long getUnitCostMinor() { return unitCostMinor; }
    public long getRestockValueMinor() { return restockValueMinor; }
}
