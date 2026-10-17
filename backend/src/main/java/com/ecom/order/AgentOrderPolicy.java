package com.ecom.order;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ecom.apikey.AgentLimits;
import com.ecom.apikey.ApiKeyService;
import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;
import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;
import com.ecom.common.web.Money;
import com.ecom.security.ActorType;
import com.ecom.security.AuthenticatedPrincipal;
import com.ecom.security.CurrentUser;

/**
 * Spending guardrails for orders placed by an agent (backend/REVIEW.md §5.3). Scopes decide what an agent may
 * do; this decides how much. A human signed in normally is never limited here — only the key is.
 *
 * <p>Checked inside the checkout transaction, after the cart row is locked, so two concurrent orders from the
 * same key cannot both slip under the daily cap.
 */
@Service
public class AgentOrderPolicy {

    private static final Duration DAY = Duration.ofDays(1);

    /** What checkout should do with an order, once the limits have had their say. */
    public record Decision(UUID apiKeyId, boolean needsApproval) {

        /** A human placed this order: no key, no approval, no limits. */
        static Decision human() {
            return new Decision(null, false);
        }

        public boolean placedByAgent() {
            return apiKeyId != null;
        }
    }

    private final ApiKeyService apiKeys;
    private final OrderRepository orders;
    private final AuditService audit;

    public AgentOrderPolicy(ApiKeyService apiKeys, OrderRepository orders, AuditService audit) {
        this.apiKeys = apiKeys;
        this.orders = orders;
        this.audit = audit;
    }

    /**
     * @throws ApiException AGENT_LIMIT_EXCEEDED if a hard cap (order value, orders per day) is exceeded —
     *     deliberately not retryable: the same order will be refused again until a human changes something.
     */
    public Decision evaluate(long orderTotalMinor, String currency) {
        AuthenticatedPrincipal principal = CurrentUser.find().orElse(null);
        if (principal == null || principal.actorType() != ActorType.API_KEY || principal.apiKeyId() == null) {
            return Decision.human();
        }
        UUID apiKeyId = principal.apiKeyId();
        AgentLimits limits = apiKeys.limitsOf(apiKeyId);

        if (limits.exceedsOrderValue(orderTotalMinor)) {
            // The order is never saved when this throws, so there is no order id to audit against: the event
            // is about the KEY being blocked, recorded against it directly. recordIndependently (not record):
            // this runs inside checkout()'s own transaction, which is about to roll back from the throw below —
            // a plain record() here would be rolled back with it and never actually persist.
            audit.recordIndependently(AuditAction.AGENT_LIMIT_BLOCKED, "api_key", apiKeyId,
                    "limit=maxOrderValueMinor,orderTotalMinor=" + orderTotalMinor);
            throw new ApiException(ErrorCode.AGENT_LIMIT_EXCEEDED,
                    "This order is above the spending limit set for this API key; a person has to place it instead",
                    Map.of("limit", "maxOrderValueMinor",
                            "maxOrderValueMinor", limits.maxOrderValueMinor(),
                            "orderTotalMinor", orderTotalMinor,
                            "orderTotal", Money.of(orderTotalMinor, currency).formatted()));
        }

        if (limits.maxOrdersPerDay() != null) {
            long placedToday = orders.countByPlacedByApiKeyIdAndCreatedAtAfter(apiKeyId, Instant.now().minus(DAY));
            if (limits.exceedsDailyCount(placedToday)) {
                audit.recordIndependently(AuditAction.AGENT_LIMIT_BLOCKED, "api_key", apiKeyId,
                        "limit=maxOrdersPerDay,ordersInLast24h=" + placedToday);
                throw new ApiException(ErrorCode.AGENT_LIMIT_EXCEEDED,
                        "This API key has reached its order limit for the last 24 hours",
                        Map.of("limit", "maxOrdersPerDay",
                                "maxOrdersPerDay", limits.maxOrdersPerDay(),
                                "ordersInLast24h", placedToday));
            }
        }

        return new Decision(apiKeyId, limits.needsApproval(orderTotalMinor));
    }
}
