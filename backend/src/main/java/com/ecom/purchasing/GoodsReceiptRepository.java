package com.ecom.purchasing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    Optional<GoodsReceipt> findByReceivedByAndIdempotencyKey(UUID receivedBy, String idempotencyKey);

    List<GoodsReceipt> findByPurchaseOrderIdOrderByReceivedAtAsc(UUID purchaseOrderId);
}
