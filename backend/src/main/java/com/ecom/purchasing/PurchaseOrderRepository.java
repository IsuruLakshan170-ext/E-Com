package com.ecom.purchasing;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    Page<PurchaseOrder> findByStatus(PurchaseOrderStatus status, Pageable pageable);

    Page<PurchaseOrder> findBySupplierId(UUID supplierId, Pageable pageable);

    Page<PurchaseOrder> findBySupplierIdAndStatus(UUID supplierId, PurchaseOrderStatus status, Pageable pageable);

    /** M6: receipts serialise on the PO row before touching lines or products. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select po from PurchaseOrder po where po.id = :id")
    Optional<PurchaseOrder> lockById(@Param("id") UUID id);
}
