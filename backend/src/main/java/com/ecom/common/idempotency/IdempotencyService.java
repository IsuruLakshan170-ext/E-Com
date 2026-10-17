package com.ecom.common.idempotency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.util.Hashing;
import com.ecom.common.util.IdempotencyKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared idempotency for every retry-safe write (M5). Usage inside the write transaction:
 * <pre>
 *   var replay = idempotency.replay(user, "createPurchaseOrder", key, fingerprint);
 *   if (replay.isPresent()) return load(replay.get());
 *   ... do the work ...
 *   idempotency.remember(user, "createPurchaseOrder", key, fingerprint, "purchase_order", po.getId());
 * </pre>
 * A concurrent duplicate hits the unique (user, operation, key) constraint and rolls back -> 409 CONFLICT
 * (retryable), and its retry then replays the winner's result.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository records;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository records, ObjectMapper objectMapper) {
        this.records = records;
        this.objectMapper = objectMapper;
    }

    /** SHA-256 of the request body as JSON (records serialise in a fixed field order). */
    public String fingerprint(Object request) {
        try {
            return Hashing.sha256Hex(objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint request", e);
        }
    }

    /** @return the id of the resource created by an earlier identical request, if any. */
    @Transactional(readOnly = true)
    public Optional<UUID> replay(UUID userId, String operation, String key, String fingerprint) {
        IdempotencyKeys.validate(key);
        return records.findByUserIdAndOperationAndIdemKey(userId, operation, key).map(r -> {
            if (!r.getRequestHash().equals(fingerprint)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used with a different request body");
            }
            return r.getResourceId();
        });
    }

    @Transactional
    public void remember(UUID userId, String operation, String key, String fingerprint, String resourceType,
                         UUID resourceId) {
        records.saveAndFlush(new IdempotencyRecord(userId, operation, key, fingerprint, resourceType, resourceId));
    }
}
