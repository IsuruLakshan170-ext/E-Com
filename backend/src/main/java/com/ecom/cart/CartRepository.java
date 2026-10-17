package com.ecom.cart;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserId(UUID userId);

    /**
     * R6: checkout locks the user's cart row first, so all checkouts of one user are serialised: the
     * unpaid-order cap can't be raced, the cart can't be stale, and a concurrent duplicate replays.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.userId = :userId")
    Optional<Cart> lockByUserId(@Param("userId") UUID userId);
}
