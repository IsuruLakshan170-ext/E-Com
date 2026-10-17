package com.ecom.apikey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    Optional<ApiKey> findByIdAndUserId(UUID id, UUID userId);

    List<ApiKey> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(UUID userId, Instant now);

    @Modifying
    @Query("update ApiKey k set k.revokedAt = :now where k.userId = :userId and k.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
