package com.ecom.catalog;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

    boolean existsByCategoryId(UUID categoryId);

    Page<Product> findByStockGreaterThan(int stock, Pageable pageable);

    /** R4: exact totals from the value pool (equals the GL 1200 Inventory balance). */
    @Query("select new com.ecom.catalog.StockTotals(cast(coalesce(sum(p.stock), 0) as Long), "
            + "cast(coalesce(sum(p.stockValueMinor), 0) as Long)) from Product p")
    StockTotals stockTotals();

    /** Active products whose AVAILABLE quantity is at or below their reorder point. */
    @Query("select p from Product p where p.active = true and p.reorderPoint > 0 "
            + "and (p.stock - p.reserved) <= p.reorderPoint")
    Page<Product> findReorderCandidates(Pageable pageable);

    @Query("select p from Product p where p.active = true and p.reorderPoint > 0 "
            + "and (p.stock - p.reserved) <= p.reorderPoint order by p.sku")
    List<Product> findAllReorderCandidates();

    /**
     * SELECT ... FOR UPDATE, always in id order so concurrent transactions lock rows in the
     * same sequence and cannot deadlock. This is what prevents overselling.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> lockAllByIds(@Param("ids") Collection<UUID> ids);

    /** BE-3: sku + name only (no entity loading), active or not. Callers use {@link #labelsById}. */
    @Query("select new com.ecom.catalog.ProductLabel(p.id, p.sku, p.name) from Product p where p.id in :ids")
    List<ProductLabel> findLabels(@Param("ids") Collection<UUID> ids);

    /** id -> label for a page of documents: one query per 500 distinct ids, never one per row. */
    default Map<UUID, ProductLabel> labelsById(Collection<UUID> ids) {
        List<UUID> distinct = ids.stream().distinct().toList();
        Map<UUID, ProductLabel> labels = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += 500) {
            findLabels(distinct.subList(from, Math.min(from + 500, distinct.size())))
                    .forEach(label -> labels.put(label.id(), label));
        }
        return labels;
    }
}
