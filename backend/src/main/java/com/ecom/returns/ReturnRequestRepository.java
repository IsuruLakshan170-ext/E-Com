package com.ecom.returns;

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

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, UUID> {

    Optional<ReturnRequest> findByIdAndUserId(UUID id, UUID userId);

    Page<ReturnRequest> findByUserId(UUID userId, Pageable pageable);

    Page<ReturnRequest> findByStatus(ReturnStatus status, Pageable pageable);

    List<ReturnRequest> findByOrderIdAndStatusNot(UUID orderId, ReturnStatus status);

    /** R1: the return row is locked before its products (receive) and after its order (refund). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReturnRequest r where r.id = :id")
    Optional<ReturnRequest> lockById(@Param("id") UUID id);

    /** Lets refund lock the ORDER row first without loading (and caching a stale copy of) the return. */
    @Query("select r.orderId from ReturnRequest r where r.id = :id")
    Optional<UUID> findOrderIdById(@Param("id") UUID id);
}
