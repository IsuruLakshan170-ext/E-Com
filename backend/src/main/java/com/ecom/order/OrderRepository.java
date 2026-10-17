package com.ecom.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<CustomerOrder, UUID> {

    Optional<CustomerOrder> findByIdAndUserId(UUID id, UUID userId);

    Page<CustomerOrder> findByUserId(UUID userId, Pageable pageable);

    Page<CustomerOrder> findByUserIdAndStatus(UUID userId, OrderStatus status, Pageable pageable);

    Page<CustomerOrder> findByStatus(OrderStatus status, Pageable pageable);

    /** BE-2: exact (normalised) order number; a page so callers share one shape with the other filters. */
    Page<CustomerOrder> findByOrderNumber(String orderNumber, Pageable pageable);

    Page<CustomerOrder> findByOrderNumberAndStatus(String orderNumber, OrderStatus status, Pageable pageable);

    long countByUserIdAndStatus(UUID userId, OrderStatus status);

    /** Per-day spending cap for one agent key (V6): orders it placed since the given moment. */
    long countByPlacedByApiKeyIdAndCreatedAtAfter(UUID placedByApiKeyId, Instant since);

    /** The owner's approval queue: their own orders an agent placed that are waiting on them. */
    // Status is redundant with approvalState after CustomerOrder.cancel()'s reconciliation (A1: PENDING
    // only ever coexists with PENDING_PAYMENT now), but kept explicit as defence in depth per the review.
    Page<CustomerOrder> findByUserIdAndApprovalStateAndStatus(
            UUID userId, ApprovalState approvalState, OrderStatus status, Pageable pageable);

    /** R1: every flow that changes an existing order locks its row FIRST (then products, sorted). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id")
    Optional<CustomerOrder> lockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id and o.userId = :userId")
    Optional<CustomerOrder> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Candidates for the expiry job (no lock: each one is then claimed in its own transaction). */
    @Query(value = "select id from orders where status = 'PENDING_PAYMENT' and expires_at < :now "
            + "order by expires_at limit :max", nativeQuery = true)
    List<UUID> findExpiredUnpaidIds(@Param("now") Instant now, @Param("max") int max);

    /**
     * R1/R3: claims ONE overdue unpaid order. SKIP LOCKED returns nothing if another transaction holds the row:
     * another job instance, or recordPayment (which locks the order row first), so a payment in flight is
     * never expired under it. Also returns nothing if the order is no longer unpaid or overdue.
     */
    @Query(value = "select id from orders where id = :id and status = 'PENDING_PAYMENT' and expires_at < :now "
            + "for update skip locked", nativeQuery = true)
    Optional<UUID> claimForExpiry(@Param("id") UUID id, @Param("now") Instant now);
}
