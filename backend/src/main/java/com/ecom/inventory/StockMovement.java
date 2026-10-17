package com.ecom.inventory;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Append-only stock ledger: every change to on-hand quantity has exactly one row here. */
@Entity
@Immutable
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MovementType type;

    /** Signed: positive = in, negative = out. */
    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_cost_minor", nullable = false)
    private long unitCostMinor;

    /** R4: exact signed value moved (in > 0, out < 0); equals this movement's GL 1200 posting. Null before V4. */
    @Column(name = "value_minor")
    private Long valueMinor;

    @Column(name = "stock_after", nullable = false)
    private int stockAfter;

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(length = 300)
    private String note;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockMovement() {
    }

    StockMovement(UUID productId, MovementType type, int quantity, long unitCostMinor, long valueMinor, int stockAfter,
                  String referenceType, UUID referenceId, String note, UUID actorId) {
        this.productId = productId;
        this.type = type;
        this.quantity = quantity;
        this.unitCostMinor = unitCostMinor;
        this.valueMinor = valueMinor;
        this.stockAfter = stockAfter;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.note = note;
        this.actorId = actorId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public MovementType getType() { return type; }
    public int getQuantity() { return quantity; }
    public long getUnitCostMinor() { return unitCostMinor; }
    public Long getValueMinor() { return valueMinor; }
    public int getStockAfter() { return stockAfter; }
    public String getReferenceType() { return referenceType; }
    public UUID getReferenceId() { return referenceId; }
    public String getNote() { return note; }
    public UUID getActorId() { return actorId; }
    public Instant getCreatedAt() { return createdAt; }
}
