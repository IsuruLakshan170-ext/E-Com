package com.ecom.finance;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A refund and its credit note. amountMinor includes taxMinor. */
@Entity
@Immutable
@Table(name = "refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "credit_note_number", nullable = false, unique = true, length = 20)
    private String creditNoteNumber;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "return_id")
    private UUID returnId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "tax_minor", nullable = false)
    private long taxMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 300)
    private String reason;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Refund() {
    }

    Refund(String creditNoteNumber, UUID orderId, UUID returnId, long amountMinor, long taxMinor, String currency,
           String reason, UUID createdBy) {
        this.creditNoteNumber = creditNoteNumber;
        this.orderId = orderId;
        this.returnId = returnId;
        this.amountMinor = amountMinor;
        this.taxMinor = taxMinor;
        this.currency = currency;
        this.reason = reason.length() > 300 ? reason.substring(0, 300) : reason;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getCreditNoteNumber() { return creditNoteNumber; }
    public UUID getOrderId() { return orderId; }
    public UUID getReturnId() { return returnId; }
    public long getAmountMinor() { return amountMinor; }
    public long getTaxMinor() { return taxMinor; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
