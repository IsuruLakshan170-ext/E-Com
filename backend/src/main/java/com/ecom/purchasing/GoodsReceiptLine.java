package com.ecom.purchasing;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

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
@Immutable
@Table(name = "goods_receipt_lines")
public class GoodsReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private GoodsReceipt receipt;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_cost_minor", nullable = false)
    private long unitCostMinor;

    protected GoodsReceiptLine() {
    }

    GoodsReceiptLine(GoodsReceipt receipt, UUID productId, int quantity, long unitCostMinor) {
        this.receipt = receipt;
        this.productId = productId;
        this.quantity = quantity;
        this.unitCostMinor = unitCostMinor;
    }

    public UUID getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public long getUnitCostMinor() { return unitCostMinor; }
}
