package com.ecom.purchasing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "po_number", nullable = false, unique = true, length = 32, updatable = false)
    private String poNumber;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(length = 1000)
    private String notes;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @BatchSize(size = 50)
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PurchaseOrder() {
    }

    PurchaseOrder(String poNumber, String currency, UUID createdBy) {
        this.poNumber = poNumber;
        this.currency = currency;
        this.createdBy = createdBy;
        this.status = PurchaseOrderStatus.DRAFT;
    }

    void editDraft(UUID supplierId, LocalDate expectedDate, String notes, List<PoLineSpec> specs) {
        if (status != PurchaseOrderStatus.DRAFT) {
            throw new IllegalStateException("Only drafts can be edited");
        }
        this.supplierId = supplierId;
        this.expectedDate = expectedDate;
        this.notes = notes;
        lines.clear();
        long total = 0;
        int lineNo = 1;
        for (PoLineSpec spec : specs) {
            PurchaseOrderLine line = new PurchaseOrderLine(this, lineNo++, spec);
            lines.add(line);
            total = Math.addExact(total, line.getLineTotalMinor());
        }
        this.totalMinor = total;
    }

    void transitionTo(PurchaseOrderStatus next) {
        if (!status.allowedNext().contains(next)) {
            throw new IllegalStateException("Illegal transition " + status + " -> " + next);
        }
        this.status = next;
    }

    void approve(UUID approverId, Instant now) {
        transitionTo(PurchaseOrderStatus.APPROVED);
        this.approvedBy = approverId;
        this.approvedAt = now;
    }

    Optional<PurchaseOrderLine> line(UUID productId) {
        return lines.stream().filter(l -> l.getProductId().equals(productId)).findFirst();
    }

    void refreshReceiptStatus() {
        boolean complete = lines.stream().allMatch(l -> l.outstanding() == 0);
        transitionTo(complete ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED);
    }

    public UUID getId() { return id; }
    public String getPoNumber() { return poNumber; }
    public UUID getSupplierId() { return supplierId; }
    public PurchaseOrderStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public long getTotalMinor() { return totalMinor; }
    public LocalDate getExpectedDate() { return expectedDate; }
    public String getNotes() { return notes; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public List<PurchaseOrderLine> getLines() { return lines; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
