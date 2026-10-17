package com.ecom.purchasing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Immutable;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** Immutable record of one delivery against a purchase order (a "GRN"). */
@Entity
@Immutable
@Table(name = "goods_receipts")
public class GoodsReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "receipt_number", nullable = false, unique = true, length = 32)
    private String receiptNumber;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(length = 500)
    private String note;

    @Column(name = "received_by", nullable = false)
    private UUID receivedBy;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "total_cost_minor", nullable = false)
    private long totalCostMinor;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.PERSIST)
    @BatchSize(size = 50)
    private List<GoodsReceiptLine> lines = new ArrayList<>();

    protected GoodsReceipt() {
    }

    GoodsReceipt(String receiptNumber, UUID purchaseOrderId, String idempotencyKey, String note, UUID receivedBy) {
        this.receiptNumber = receiptNumber;
        this.purchaseOrderId = purchaseOrderId;
        this.idempotencyKey = idempotencyKey;
        this.note = note;
        this.receivedBy = receivedBy;
        this.receivedAt = Instant.now();
    }

    void addLine(UUID productId, int quantity, long unitCostMinor) {
        lines.add(new GoodsReceiptLine(this, productId, quantity, unitCostMinor));
        totalCostMinor = Math.addExact(totalCostMinor, Math.multiplyExact((long) quantity, unitCostMinor));
    }

    public UUID getId() { return id; }
    public String getReceiptNumber() { return receiptNumber; }
    public UUID getPurchaseOrderId() { return purchaseOrderId; }
    public String getNote() { return note; }
    public UUID getReceivedBy() { return receivedBy; }
    public Instant getReceivedAt() { return receivedAt; }
    public long getTotalCostMinor() { return totalCostMinor; }
    public List<GoodsReceiptLine> getLines() { return lines; }
}
