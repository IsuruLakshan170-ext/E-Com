package com.ecom.audit;

import java.time.Instant;
import java.util.UUID;

import com.ecom.security.ActorType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Append-only security/business event log. No update paths exist for this entity. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20, updatable = false)
    private ActorType actorType;

    @Column(name = "api_key_id", updatable = false)
    private UUID apiKeyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50, updatable = false)
    private AuditAction action;

    @Column(name = "target_type", length = 50, updatable = false)
    private String targetType;

    @Column(name = "target_id", length = 64, updatable = false)
    private String targetId;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Column(length = 1000, updatable = false)
    private String details;

    protected AuditEvent() {
    }

    AuditEvent(UUID actorId, ActorType actorType, UUID apiKeyId, AuditAction action, String targetType,
               String targetId, String ipAddress, String requestId, String details) {
        this.occurredAt = Instant.now();
        this.actorId = actorId;
        this.actorType = actorType;
        this.apiKeyId = apiKeyId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.ipAddress = ipAddress;
        this.requestId = requestId;
        this.details = details;
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getActorId() { return actorId; }
    public ActorType getActorType() { return actorType; }
    public UUID getApiKeyId() { return apiKeyId; }
    public AuditAction getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getIpAddress() { return ipAddress; }
    public String getRequestId() { return requestId; }
    public String getDetails() { return details; }
}
