package com.ecom.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.ecom.audit.AuditService;

import jakarta.persistence.LockTimeoutException;

/** R2: lock timeouts and deadlocks are 409 CONFLICT with retryable=true, never a 500. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(AuditService.class));

    @Test
    void lockFailuresAreRetryableConflicts() {
        for (Exception ex : new Exception[] {
                new CannotAcquireLockException("deadlock detected"),
                new PessimisticLockingFailureException("lock timeout"),
                new LockTimeoutException("lock timeout")}) {
            ResponseEntity<ProblemDetail> response = handler.handleLockConflict(ex);
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(response.getBody().getProperties())
                    .containsEntry("code", "CONFLICT")
                    .containsEntry("retryable", true);
        }
    }
}
