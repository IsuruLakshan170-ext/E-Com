package com.ecom.apikey;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A personal, scoped, expiring credential for automated clients such as the MCP server.
 * The key always acts as its owner, never with more than the owner's role allows.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 16, updatable = false)
    private String prefix;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64, updatable = false)
    private String keyHash;

    @Column(nullable = false, length = 500, updatable = false)
    private String scopes;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Spending guardrails (V6). Null = no limit of that kind. Set once at creation: the key IS the unit of
    // delegation, so changing the deal means revoking it and issuing another.
    @Column(name = "max_order_value_minor", updatable = false)
    private Long maxOrderValueMinor;

    @Column(name = "max_orders_per_day", updatable = false)
    private Integer maxOrdersPerDay;

    @Column(name = "require_approval_above_minor", updatable = false)
    private Long requireApprovalAboveMinor;

    protected ApiKey() {
    }

    ApiKey(UUID userId, String name, String prefix, String keyHash, Set<String> scopes, Instant expiresAt,
           AgentLimits limits) {
        this.userId = userId;
        this.name = name;
        this.prefix = prefix;
        this.keyHash = keyHash;
        this.scopes = String.join(" ", scopes);
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.maxOrderValueMinor = limits.maxOrderValueMinor();
        this.maxOrdersPerDay = limits.maxOrdersPerDay();
        this.requireApprovalAboveMinor = limits.requireApprovalAboveMinor();
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    void markUsed(Instant now) {
        lastUsedAt = now;
    }

    public Set<String> scopeSet() {
        return new LinkedHashSet<>(Arrays.asList(scopes.split(" ")));
    }

    public AgentLimits limits() {
        return new AgentLimits(maxOrderValueMinor, maxOrdersPerDay, requireApprovalAboveMinor);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public String getPrefix() { return prefix; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
