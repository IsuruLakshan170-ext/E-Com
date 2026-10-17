package com.ecom.returns;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A return merchandise authorisation (RMA). */
@Entity
@Table(name = "return_requests")
public class ReturnRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rma_number", nullable = false, unique = true, length = 32, updatable = false)
    private String rmaNumber;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReturnStatus status;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "received_by")
    private UUID receivedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "refund_id")
    private UUID refundId;

    @OneToMany(mappedBy = "returnRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    private List<ReturnLine> lines = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ReturnRequest() {
    }

    ReturnRequest(String rmaNumber, UUID orderId, UUID userId, String reason) {
        this.rmaNumber = rmaNumber;
        this.orderId = orderId;
        this.userId = userId;
        this.reason = reason;
        this.status = ReturnStatus.REQUESTED;
    }

    void addLine(UUID productId, String sku, String name, int quantity, long unitPriceMinor, long unitCostMinor,
                 long restockValueMinor) {
        lines.add(new ReturnLine(this, productId, sku, name, quantity, unitPriceMinor, unitCostMinor, restockValueMinor));
    }

    void transitionTo(ReturnStatus next) {
        if (!status.allowedNext().contains(next)) {
            throw new IllegalStateException("Illegal transition " + status + " -> " + next);
        }
        this.status = next;
    }

    void decide(ReturnStatus decision, UUID decidedBy, String note) {
        transitionTo(decision);
        this.decidedBy = decidedBy;
        this.resolutionNote = note;
    }

    void markReceived(UUID receiverId, Instant now) {
        transitionTo(ReturnStatus.RECEIVED);
        this.receivedBy = receiverId;
        this.receivedAt = now;
    }

    void markRefunded(UUID refundId) {
        transitionTo(ReturnStatus.REFUNDED);
        this.refundId = refundId;
    }

    long merchandiseTotalMinor() {
        return lines.stream().mapToLong(ReturnLine::lineTotalMinor).reduce(0L, Math::addExact);
    }

    long costTotalMinor() {
        return lines.stream().mapToLong(ReturnLine::lineCostMinor).reduce(0L, Math::addExact);
    }

    public UUID getId() { return id; }
    public String getRmaNumber() { return rmaNumber; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public ReturnStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public String getResolutionNote() { return resolutionNote; }
    public UUID getDecidedBy() { return decidedBy; }
    public UUID getReceivedBy() { return receivedBy; }
    public Instant getReceivedAt() { return receivedAt; }
    public UUID getRefundId() { return refundId; }
    public List<ReturnLine> getLines() { return lines; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
