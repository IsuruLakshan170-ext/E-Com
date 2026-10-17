package com.ecom.inventory;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    Page<StockMovement> findByProductId(UUID productId, Pageable pageable);

    Page<StockMovement> findByType(MovementType type, Pageable pageable);

    Page<StockMovement> findByProductIdAndType(UUID productId, MovementType type, Pageable pageable);
}
