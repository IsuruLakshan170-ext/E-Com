package com.ecom.apikey;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class ApiKeyDtos {

    private ApiKeyDtos() {
    }

    /**
     * The three limit fields are optional; each null means "no limit of that kind", which is how every key
     * behaved before they existed. They only bite for orders placed BY this key — a human signed in normally
     * is never limited by them.
     */
    public record CreateApiKeyRequest(
            @NotBlank @Size(max = 100) String name,
            @NotEmpty @Size(max = 20) Set<@NotBlank @Size(max = 50) String> scopes,
            @Min(1) @Max(365) Integer expiresInDays,
            @PositiveOrZero @Max(1_000_000_000L) Long maxOrderValueMinor,
            @PositiveOrZero @Max(10_000) Integer maxOrdersPerDay,
            @PositiveOrZero @Max(1_000_000_000L) Long requireApprovalAboveMinor) {

        AgentLimits limits() {
            return new AgentLimits(maxOrderValueMinor, maxOrdersPerDay, requireApprovalAboveMinor);
        }
    }

    public record ApiKeyResponse(UUID id, String name, String prefix, Set<String> scopes, Instant expiresAt,
                                 Instant lastUsedAt, Instant revokedAt, Instant createdAt, boolean active,
                                 AgentLimits limits) {
        static ApiKeyResponse from(ApiKey key) {
            return new ApiKeyResponse(key.getId(), key.getName(), key.getPrefix(), key.scopeSet(), key.getExpiresAt(),
                    key.getLastUsedAt(), key.getRevokedAt(), key.getCreatedAt(), key.isActiveAt(Instant.now()),
                    key.limits());
        }
    }

    /** The only response that ever contains the raw key. */
    public record CreatedApiKeyResponse(ApiKeyResponse key, String apiKey, String warning) {
    }
}
