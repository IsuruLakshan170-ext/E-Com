package com.ecom.common.idempotency;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String operation;

    @Column(name = "idem_key", nullable = false, length = 100)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    IdempotencyRecord(UUID userId, String operation, String idemKey, String requestHash, String resourceType,
                      UUID resourceId) {
        this.userId = userId;
        this.operation = operation;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.createdAt = Instant.now();
    }

    public String getRequestHash() { return requestHash; }
    public UUID getResourceId() { return resourceId; }
}
