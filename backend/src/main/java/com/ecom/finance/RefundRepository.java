package com.ecom.finance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    @Query("select new com.ecom.finance.AmountTotals(count(r), coalesce(sum(r.amountMinor), 0L), coalesce(sum(r.taxMinor), 0L)) "
            + "from Refund r where r.createdAt >= :from and r.createdAt < :to")
    AmountTotals totalsBetween(@Param("from") Instant from, @Param("to") Instant to);
}
