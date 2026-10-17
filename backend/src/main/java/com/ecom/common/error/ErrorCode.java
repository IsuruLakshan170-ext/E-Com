package com.ecom.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes. Clients (including the MCP server) branch on these,
 * never on the human-readable message.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, false),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, false),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, false),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, false),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, false),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, false),
    FORBIDDEN(HttpStatus.FORBIDDEN, false),
    SELF_APPROVAL_NOT_ALLOWED(HttpStatus.FORBIDDEN, false),
    SEGREGATION_OF_DUTIES(HttpStatus.FORBIDDEN, false),
    NOT_FOUND(HttpStatus.NOT_FOUND, false),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, false),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false),
    CONFLICT(HttpStatus.CONFLICT, true),
    EMAIL_TAKEN(HttpStatus.CONFLICT, false),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, false),
    PRODUCT_UNAVAILABLE(HttpStatus.CONFLICT, false),
    PRICE_CHANGED(HttpStatus.CONFLICT, false),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, false),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, false),
    CART_EMPTY(HttpStatus.UNPROCESSABLE_ENTITY, false),
    LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    /** An agent key's own spending guardrail refused this (backend/REVIEW.md §5.3), not a scope or stock problem. */
    AGENT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, false),
    OVER_RECEIPT(HttpStatus.UNPROCESSABLE_ENTITY, false),
    RETURN_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    REFUND_EXCEEDS_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, false),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, true);

    private final HttpStatus status;
    private final boolean retryable;

    ErrorCode(HttpStatus status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
