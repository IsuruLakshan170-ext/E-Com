package com.ecom.finance;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByOrderId(UUID orderId);

    List<Invoice> findByOrderIdIn(Collection<UUID> orderIds);

    @Query("select new com.ecom.finance.AmountTotals(count(i), coalesce(sum(i.subtotalMinor), 0L), coalesce(sum(i.taxMinor), 0L)) "
            + "from Invoice i where i.issuedAt >= :from and i.issuedAt < :to")
    AmountTotals totalsBetween(@Param("from") Instant from, @Param("to") Instant to);
}
