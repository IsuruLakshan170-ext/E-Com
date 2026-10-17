package com.ecom.finance;

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

/** One full payment per order (unique order_id), so a payment can never be booked twice. */
@Entity
@Immutable
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(length = 100)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentOutcome outcome;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    Payment(UUID orderId, long amountMinor, String currency, PaymentMethod method, String reference, UUID recordedBy,
            PaymentOutcome outcome) {
        this.orderId = orderId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.method = method;
        this.reference = reference;
        this.outcome = outcome;
        this.recordedBy = recordedBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public PaymentMethod getMethod() { return method; }
    public String getReference() { return reference; }
    public PaymentOutcome getOutcome() { return outcome; }
    public UUID getRecordedBy() { return recordedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
