package com.ecom.purchasing;

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
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "quantity_ordered", nullable = false)
    private int quantityOrdered;

    @Column(name = "quantity_received", nullable = false)
    private int quantityReceived;

    @Column(name = "unit_cost_minor", nullable = false)
    private long unitCostMinor;

    @Column(name = "line_total_minor", nullable = false)
    private long lineTotalMinor;

    protected PurchaseOrderLine() {
    }

    PurchaseOrderLine(PurchaseOrder purchaseOrder, int lineNo, PoLineSpec spec) {
        this.purchaseOrder = purchaseOrder;
        this.lineNo = lineNo;
        this.productId = spec.productId();
        this.quantityOrdered = spec.quantity();
        this.unitCostMinor = spec.unitCostMinor();
        this.lineTotalMinor = Math.multiplyExact((long) spec.quantity(), spec.unitCostMinor());
    }

    int outstanding() {
        return quantityOrdered - quantityReceived;
    }

    void receive(int quantity) {
        if (quantity <= 0 || quantity > outstanding()) {
            throw new IllegalStateException("Invalid receipt quantity");
        }
        quantityReceived += quantity;
    }

    public int getLineNo() { return lineNo; }
    public UUID getProductId() { return productId; }
    public int getQuantityOrdered() { return quantityOrdered; }
    public int getQuantityReceived() { return quantityReceived; }
    public long getUnitCostMinor() { return unitCostMinor; }
    public long getLineTotalMinor() { return lineTotalMinor; }
}
