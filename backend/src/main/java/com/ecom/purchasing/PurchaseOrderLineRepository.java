package com.ecom.purchasing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

    interface OutstandingQuantity {
        UUID getProductId();

        Long getQuantity();
    }

    @Query("select l.productId as productId, cast(sum(l.quantityOrdered - l.quantityReceived) as Long) as quantity "
            + "from PurchaseOrderLine l where l.purchaseOrder.status in :statuses group by l.productId")
    List<OutstandingQuantity> outstandingByProduct(@Param("statuses") Collection<PurchaseOrderStatus> statuses);
}
