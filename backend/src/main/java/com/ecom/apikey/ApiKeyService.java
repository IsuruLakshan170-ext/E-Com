package com.ecom.apikey;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.apikey.ApiKeyDtos.ApiKeyResponse;
import com.ecom.apikey.ApiKeyDtos.CreateApiKeyRequest;
import com.ecom.apikey.ApiKeyDtos.CreatedApiKeyResponse;
import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.audit.AuditService.AuditEventResponse;
import com.ecom.audit.AuditService.AuditQuery;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.idempotency.IdempotencyService;
import com.ecom.common.util.Hashing;
import com.ecom.common.web.PageResponse;
import com.ecom.security.ActorType;
import com.ecom.security.ApiKeyAuthentication;
import com.ecom.security.AuthenticatedPrincipal;
import com.ecom.user.CredentialsRevokedEvent;
import com.ecom.user.User;
import com.ecom.user.UserRepository;

@Service
public class ApiKeyService {

    static final String KEY_PREFIX = "ek_";
    private static final int MAX_ACTIVE_KEYS_PER_USER = 10;
    private static final int DEFAULT_EXPIRY_DAYS = 90;
    private static final Duration LAST_USED_WRITE_INTERVAL = Duration.ofMinutes(5);

    private final ApiKeyRepository apiKeys;
    private final UserRepository users;
    private final AuditService audit;
    private final IdempotencyService idempotency;

    public ApiKeyService(ApiKeyRepository apiKeys, UserRepository users, AuditService audit,
                         IdempotencyService idempotency) {
        this.apiKeys = apiKeys;
        this.users = users;
        this.audit = audit;
        this.idempotency = idempotency;
    }

    public record CreateResult(CreatedApiKeyResponse response, boolean replayed) {
    }

    /**
     * Idempotency-Key is optional here (only interactive users create keys). A replay returns the key's
     * metadata but NOT the secret, which is never stored.
     */
    @Transactional
    public CreateResult create(UUID userId, String idempotencyKey, CreateApiKeyRequest request) {
        String fingerprint = idempotencyKey == null ? null : idempotency.fingerprint(request);
        if (idempotencyKey != null) {
            var replay = idempotency.replay(userId, "createApiKey", idempotencyKey, fingerprint);
            if (replay.isPresent()) {
                ApiKey existing = apiKeys.findByIdAndUserId(replay.get(), userId)
                        .orElseThrow(() -> ApiException.notFound("API key"));
                return new CreateResult(new CreatedApiKeyResponse(ApiKeyResponse.from(existing), null,
                        "This key was already created by an earlier request; its secret cannot be shown again."), true);
            }
        }
        CreatedApiKeyResponse created = createKey(userId, request);
        if (idempotencyKey != null) {
            idempotency.remember(userId, "createApiKey", idempotencyKey, fingerprint, "api_key", created.key().id());
        }
        return new CreateResult(created, false);
    }

    /** H3: password change / admin revoke / "sign out everywhere" kills every key of the user. */
    @EventListener
    public void onCredentialsRevoked(CredentialsRevokedEvent event) {
        int revoked = apiKeys.revokeAllForUser(event.userId(), Instant.now());
        if (revoked > 0) {
            audit.record(AuditAction.API_KEY_REVOKED, "user", event.userId(), revoked + " key(s), reason=" + event.reason());
        }
    }

    private CreatedApiKeyResponse createKey(UUID userId, CreateApiKeyRequest request) {
        User owner = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));

        Set<String> requested = new TreeSet<>(request.scopes());
        List<String> notAllowed = requested.stream().filter(s -> !owner.getRole().scopes().contains(s)).toList();
        if (!notAllowed.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Requested scopes exceed what your account can grant",
                    Map.of("invalidScopes", notAllowed, "grantableScopes", owner.getRole().scopes()));
        }

        Instant now = Instant.now();
        if (apiKeys.countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, now) >= MAX_ACTIVE_KEYS_PER_USER) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                    "At most " + MAX_ACTIVE_KEYS_PER_USER + " active API keys per user; revoke one first");
        }

        int days = request.expiresInDays() == null ? DEFAULT_EXPIRY_DAYS : request.expiresInDays();
        String rawKey = KEY_PREFIX + Hashing.randomUrlToken(32);
        ApiKey key = apiKeys.save(new ApiKey(userId, request.name().trim(), rawKey.substring(0, 11),
                Hashing.sha256Hex(rawKey), requested, now.plus(days, ChronoUnit.DAYS), request.limits()));

        audit.record(AuditAction.API_KEY_CREATED, "api_key", key.getId(),
                "scopes=" + String.join(",", requested) + ",limits=" + request.limits());
        return new CreatedApiKeyResponse(ApiKeyResponse.from(key), rawKey,
                "Store this key now in a secret manager; it cannot be shown again.");
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list(UUID userId) {
        return apiKeys.findByUserIdOrderByCreatedAtDesc(userId).stream().map(ApiKeyResponse::from).toList();
    }

    /**
     * The spending guardrails of the key currently acting, for the order module to enforce
     * (backend/REVIEW.md §5.3). A key that has since been deleted gets {@link AgentLimits#NONE} rather than an
     * error: it cannot be acting if it does not exist, so this is unreachable in practice.
     */
    @Transactional(readOnly = true)
    public AgentLimits limitsOf(UUID apiKeyId) {
        return apiKeys.findById(apiKeyId).map(ApiKey::limits).orElse(AgentLimits.NONE);
    }

    /**
     * "What did my agent do?" — every audited action taken by one of the caller's own keys. The ownership
     * check is the point: this reads the audit log, which is otherwise admin-only (audit:read), so it must
     * never return events for a key the caller doesn't own.
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> activity(UUID userId, UUID keyId, int page, int size) {
        ApiKey key = apiKeys.findByIdAndUserId(keyId, userId).orElseThrow(() -> ApiException.notFound("API key"));
        return audit.search(new AuditQuery(null, null, null, key.getId(), null, null, null, null), page, size);
    }

    @Transactional
    public void revoke(UUID userId, UUID keyId) {
        ApiKey key = apiKeys.findByIdAndUserId(keyId, userId).orElseThrow(() -> ApiException.notFound("API key"));
        key.revoke(Instant.now());
        audit.record(AuditAction.API_KEY_REVOKED, "api_key", key.getId(), null);
    }

    /**
     * Effective scopes = key scopes INTERSECT owner's current role scopes, so demoting or disabling
     * a user immediately narrows or kills all of their keys.
     */
    @Transactional
    public Optional<ApiKeyAuthentication> authenticate(String rawKey) {
        if (!rawKey.startsWith(KEY_PREFIX) || rawKey.length() > 100) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Optional<ApiKey> found = apiKeys.findByKeyHash(Hashing.sha256Hex(rawKey)).filter(k -> k.isActiveAt(now));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKey key = found.get();
        Optional<User> owner = users.findById(key.getUserId()).filter(User::isEnabled);
        if (owner.isEmpty()) {
            return Optional.empty();
        }

        Set<String> effective = new TreeSet<>(key.scopeSet());
        effective.retainAll(owner.get().getRole().scopes());

        if (key.getLastUsedAt() == null || key.getLastUsedAt().plus(LAST_USED_WRITE_INTERVAL).isBefore(now)) {
            key.markUsed(now);
        }
        return Optional.of(new ApiKeyAuthentication(
                new AuthenticatedPrincipal(key.getUserId(), ActorType.API_KEY, key.getId()), effective));
    }
}
