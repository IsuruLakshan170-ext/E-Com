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

/** Issued once, on payment. Immutable; corrections are made with credit notes (refunds). */
@Entity
@Immutable
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 20)
    private String invoiceNumber;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "subtotal_minor", nullable = false)
    private long subtotalMinor;

    @Column(name = "tax_minor", nullable = false)
    private long taxMinor;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    protected Invoice() {
    }

    Invoice(String invoiceNumber, UUID orderId, UUID userId, String currency, long subtotalMinor, long taxMinor,
            long totalMinor) {
        this.invoiceNumber = invoiceNumber;
        this.orderId = orderId;
        this.userId = userId;
        this.currency = currency;
        this.subtotalMinor = subtotalMinor;
        this.taxMinor = taxMinor;
        this.totalMinor = totalMinor;
        this.issuedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public String getCurrency() { return currency; }
    public long getSubtotalMinor() { return subtotalMinor; }
    public long getTaxMinor() { return taxMinor; }
    public long getTotalMinor() { return totalMinor; }
    public Instant getIssuedAt() { return issuedAt; }
}
