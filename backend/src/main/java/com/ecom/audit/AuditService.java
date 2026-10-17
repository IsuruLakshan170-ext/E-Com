package com.ecom.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.common.web.ClientIp;
import com.ecom.common.web.PageResponse;
import com.ecom.common.web.RequestIdFilter;
import com.ecom.security.ActorType;
import com.ecom.security.AuthenticatedPrincipal;
import com.ecom.security.CurrentUser;

import jakarta.persistence.criteria.Predicate;

/**
 * Inside a business transaction, an event is recorded only if the change commits. Outside one
 * (security filters, error handlers) each event is saved in its own transaction.
 */
@Service
public class AuditService {

    public record AuditEventResponse(UUID id, Instant occurredAt, UUID actorId, ActorType actorType,
                                     UUID apiKeyId, AuditAction action, String targetType, String targetId,
                                     String ipAddress, String requestId, String details) {
        static AuditEventResponse from(AuditEvent e) {
            return new AuditEventResponse(e.getId(), e.getOccurredAt(), e.getActorId(), e.getActorType(),
                    e.getApiKeyId(), e.getAction(), e.getTargetType(), e.getTargetId(), e.getIpAddress(),
                    e.getRequestId(), e.getDetails());
        }
    }

    /** All filters are optional and combined with AND (M9). */
    public record AuditQuery(AuditAction action, UUID actorId, ActorType actorType, UUID apiKeyId,
                             String targetType, String targetId, Instant from, Instant to) {
    }

    private final AuditEventRepository repository;
    private final AuditThrottle throttle = new AuditThrottle(System::nanoTime, Duration.ofMinutes(1));

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * R8: for high-volume security events (ACCESS_DENIED, API_KEY_REJECTED, RATE_LIMITED): at most one row
     * per (action, bucket) per minute. The row's details end with "occurrences=N": the events it stands for
     * since the previous row of that bucket (events after the last row of a burst are only counted in memory).
     */
    public void recordThrottled(String bucket, AuditAction action, String targetType, Object targetId, String details) {
        OptionalLong occurrences = throttle.tryAcquire(action.name() + "|" + bucket);
        if (occurrences.isPresent()) {
            record(action, targetType, targetId,
                    (details == null ? "" : details + ", ") + "occurrences=" + occurrences.getAsLong());
        }
    }

    /** Throttle bucket for the current caller: API key, else user, else client IP. */
    public static String callerBucket(String clientIp) {
        return CurrentUser.find()
                .map(p -> p.apiKeyId() != null ? "key:" + p.apiKeyId() : "user:" + p.userId())
                .orElse("ip:" + clientIp);
    }

    public void record(AuditAction action, String targetType, Object targetId, String details) {
        AuthenticatedPrincipal principal = CurrentUser.find().orElse(null);
        if (principal == null) {
            recordAs(null, ActorType.ANONYMOUS, null, action, targetType, targetId, details);
        } else {
            recordAs(principal.userId(), principal.actorType(), principal.apiKeyId(), action, targetType, targetId, details);
        }
    }

    /**
     * Same as {@link #record}, but commits in its own transaction regardless of what the caller's own
     * transaction does afterwards — for an event that must survive even when the caller goes on to roll back
     * (e.g. throwing right after this call, the same way {@code recordThrottled} already must: that one is
     * called from filters that run with no transaction open at all, so it gets its own implicitly; this gives
     * the same guarantee to a caller that IS inside one). {@code REQUIRES_NEW} only works through a real Spring
     * proxy, which is exactly what calling this bean's own public method, from a different bean, gives you —
     * never call this from inside {@link AuditService} itself expecting the same effect.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditAction action, String targetType, Object targetId, String details) {
        record(action, targetType, targetId, details);
    }

    public void recordAs(UUID actorId, ActorType actorType, UUID apiKeyId, AuditAction action,
                         String targetType, Object targetId, String details) {
        repository.save(new AuditEvent(actorId, actorType, apiKeyId, action, targetType,
                targetId == null ? null : truncate(targetId.toString(), 64), ClientIp.current(),
                MDC.get(RequestIdFilter.MDC_KEY), truncate(details, 1000)));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> search(AuditQuery q, int page, int size) {
        Specification<AuditEvent> spec = (root, cq, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (q.action() != null) p.add(cb.equal(root.get("action"), q.action()));
            if (q.actorId() != null) p.add(cb.equal(root.get("actorId"), q.actorId()));
            if (q.actorType() != null) p.add(cb.equal(root.get("actorType"), q.actorType()));
            if (q.apiKeyId() != null) p.add(cb.equal(root.get("apiKeyId"), q.apiKeyId()));
            if (q.targetType() != null) p.add(cb.equal(root.get("targetType"), q.targetType()));
            if (q.targetId() != null) p.add(cb.equal(root.get("targetId"), q.targetId()));
            if (q.from() != null) p.add(cb.greaterThanOrEqualTo(root.<Instant>get("occurredAt"), q.from()));
            if (q.to() != null) p.add(cb.lessThan(root.<Instant>get("occurredAt"), q.to()));
            return cb.and(p.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by("id")));
        return PageResponse.of(repository.findAll(spec, pageable).map(AuditEventResponse::from));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
