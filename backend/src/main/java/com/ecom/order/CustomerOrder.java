package com.ecom.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ecom.tax.TaxService;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Named CustomerOrder because ORDER is a reserved word in JPQL/SQL. */
@Entity
@Table(name = "orders")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_number", nullable = false, unique = true, length = 32, updatable = false)
    private String orderNumber;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 20)
    private CancelReason cancelReason;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "subtotal_minor", nullable = false)
    private long subtotalMinor;

    @Column(name = "tax_rate_bp", nullable = false)
    private int taxRateBasisPoints;

    @Column(name = "tax_minor", nullable = false)
    private long taxMinor;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "refunded_tax_minor", nullable = false)
    private long refundedTaxMinor;

    @Embedded
    private ShippingAddress shippingAddress;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    private List<OrderItem> items = new ArrayList<>();

    /** Unpaid orders past this instant are cancelled by OrderExpiryJob and release their stock. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    // Agent guardrails (V6). placedByApiKeyId is null for an order a human placed; approvalState is null
    // unless that key's threshold made this order need a human's sign-off before it can be paid.
    @Column(name = "placed_by_api_key_id", updatable = false)
    private UUID placedByApiKeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_state", length = 20)
    private ApprovalState approvalState;

    @Column(name = "approval_decided_at")
    private Instant approvalDecidedAt;

    @Column(name = "approval_decided_by")
    private UUID approvalDecidedBy;

    protected CustomerOrder() {
    }

    CustomerOrder(String orderNumber, UUID userId, String currency, ShippingAddress shippingAddress, Instant expiresAt) {
        this.orderNumber = orderNumber;
        this.userId = userId;
        this.currency = currency;
        this.shippingAddress = shippingAddress;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.expiresAt = expiresAt;
    }

    /** Records which agent key placed this, and whether it has to wait for its owner before being paid. */
    void placedByAgent(UUID apiKeyId, boolean needsApproval) {
        this.placedByApiKeyId = apiKeyId;
        this.approvalState = needsApproval ? ApprovalState.PENDING : null;
    }

    /** Blocks payment, not the order itself: everything else (expiry, cancellation, stock) behaves normally. */
    public boolean isAwaitingApproval() {
        return approvalState == ApprovalState.PENDING;
    }

    void decideApproval(ApprovalState decision, UUID decidedBy, Instant now) {
        if (!isAwaitingApproval()) {
            throw new IllegalStateException("Order is not awaiting approval");
        }
        this.approvalState = decision;
        this.approvalDecidedBy = decidedBy;
        this.approvalDecidedAt = now;
    }

    void addItem(UUID productId, String sku, String productName, long unitPriceMinor, long unitCostMinor, int quantity) {
        OrderItem item = new OrderItem(this, productId, sku, productName, unitPriceMinor, unitCostMinor, quantity);
        items.add(item);
        subtotalMinor = Math.addExact(subtotalMinor, item.getLineTotalMinor());
        totalMinor = Math.addExact(subtotalMinor, taxMinor);
    }

    /** Prices are tax-exclusive; tax is computed once on the order subtotal. */
    void applyTax(int rateBasisPoints) {
        this.taxRateBasisPoints = rateBasisPoints;
        this.taxMinor = TaxService.taxFor(subtotalMinor, rateBasisPoints);
        this.totalMinor = Math.addExact(subtotalMinor, taxMinor);
    }

    /** Guarded lifecycle change; also stamps paid/shipped/delivered times. Cancelling goes through {@link #cancel}. */
    public void transitionTo(OrderStatus next, Instant now) {
        if (next == OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("Use cancel(reason, now) to cancel an order");
        }
        move(next, now);
    }

    /**
     * Every cancellation path (customer, staff, expiry) goes through here, which is exactly why this is the
     * one place that reconciles approval state too (review round 2, A1): an order awaiting approval that then
     * gets cancelled or expires must not be left showing PENDING forever. {@link #decideApproval} already
     * moved it off PENDING for an explicit human reject, so this only ever fires for the other paths.
     */
    public void cancel(CancelReason reason, Instant now) {
        move(OrderStatus.CANCELLED, now);
        this.cancelReason = reason;
        if (approvalState == ApprovalState.PENDING) {
            approvalState = reason == CancelReason.EXPIRED ? ApprovalState.EXPIRED : ApprovalState.REJECTED;
            approvalDecidedAt = now; // approvalDecidedBy stays null: nobody decided this, cancellation did
        }
    }

    /** True for orders cancelled by the payment-deadline job; only these accept a late payment (R3). */
    public boolean isExpired() {
        return status == OrderStatus.CANCELLED && cancelReason == CancelReason.EXPIRED;
    }

    /**
     * R3: a late payment arrived for an expired order and its stock could be re-reserved: the order is PAID
     * again. Deliberately outside the normal transition table (CANCELLED is otherwise final).
     */
    public void reinstateAsPaid(Instant now) {
        if (!isExpired()) {
            throw new IllegalStateException("Only expired orders can be reinstated");
        }
        this.status = OrderStatus.PAID;
        this.cancelReason = null;
        this.paidAt = now;
        this.expiresAt = null;
    }

    private void move(OrderStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal transition " + status + " -> " + next);
        }
        switch (next) {
            case PAID -> {
                paidAt = now;
                expiresAt = null;
            }
            case SHIPPED -> shippedAt = now;
            case DELIVERED -> deliveredAt = now;
            default -> { }
        }
        this.status = next;
    }

    public void recordRefund(long amountMinor, long refundTaxMinor) {
        if (amountMinor <= 0 || amountMinor > refundableMinor() || refundTaxMinor < 0
                || refundTaxMinor > taxMinor - refundedTaxMinor) {
            throw new IllegalStateException("Invalid refund amount");
        }
        refundedMinor += amountMinor;
        refundedTaxMinor += refundTaxMinor;
    }

    public long refundableMinor() {
        return totalMinor - refundedMinor;
    }

    public Optional<OrderItem> item(UUID productId) {
        return items.stream().filter(i -> i.getProductId().equals(productId)).findFirst();
    }

    public UUID getId() { return id; }
    public UUID getPlacedByApiKeyId() { return placedByApiKeyId; }
    public ApprovalState getApprovalState() { return approvalState; }
    public Instant getApprovalDecidedAt() { return approvalDecidedAt; }
    public UUID getApprovalDecidedBy() { return approvalDecidedBy; }
    public String getOrderNumber() { return orderNumber; }
    public UUID getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public CancelReason getCancelReason() { return cancelReason; }
    public String getCurrency() { return currency; }
    public long getSubtotalMinor() { return subtotalMinor; }
    public int getTaxRateBasisPoints() { return taxRateBasisPoints; }
    public long getTaxMinor() { return taxMinor; }
    public long getTotalMinor() { return totalMinor; }
    public long getRefundedMinor() { return refundedMinor; }
    public long getRefundedTaxMinor() { return refundedTaxMinor; }
    public ShippingAddress getShippingAddress() { return shippingAddress; }
    public List<OrderItem> getItems() { return items; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getPaidAt() { return paidAt; }
    public Instant getShippedAt() { return shippedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
