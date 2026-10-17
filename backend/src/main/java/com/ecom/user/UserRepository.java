package com.ecom.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(Role role);

    long countByRoleAndEnabledTrue(Role role);

    Page<User> findByRole(Role role, Pageable pageable);

    @Query("select new com.ecom.user.TokenState(u.enabled, u.tokenVersion) from User u where u.id = :id")
    Optional<TokenState> findTokenState(@Param("id") UUID id);

    /** BE-1: names and emails for a page of orders in one query (callers skip the call for an empty page). */
    @Query("select new com.ecom.user.UserSummary(u.id, u.email, u.fullName) from User u where u.id in :ids")
    List<UserSummary> findSummaries(@Param("ids") Collection<UUID> ids);
}
