package com.ecom.common.error;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ecom.audit.AuditAction;
import com.ecom.audit.AuditService;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/** Maps every exception to problem+json. Internal details (stack traces, SQL) never reach the client. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
        if (ex.code().status().value() == 403) {
            auditDenied(request, ex.code().name());
        }
        ProblemDetail pd = Problems.of(ex.code(), ex.getMessage());
        ex.details().forEach(pd::setProperty);
        return respond(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return validation(errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleParamValidation(HandlerMethodValidationException ex) {
        List<Map<String, String>> errors = ex.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(err -> fieldError(
                                Objects.toString(result.getMethodParameter().getParameterName(), "parameter"),
                                err.getDefaultMessage())))
                .toList();
        return validation(errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> fieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validation(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        return respond(Problems.of(ErrorCode.MALFORMED_REQUEST,
                "Request body is missing, malformed, or contains unknown fields or invalid enum values"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(Problems.of(ErrorCode.VALIDATION_FAILED, "Invalid value for parameter '" + ex.getName() + "'"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex) {
        if ("Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())) {
            return respond(Problems.of(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Idempotency-Key header is required for this operation"));
        }
        return respond(Problems.of(ErrorCode.VALIDATION_FAILED, "Missing required header '" + ex.getHeaderName() + "'"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParam(MissingServletRequestParameterException ex) {
        return respond(Problems.of(ErrorCode.VALIDATION_FAILED, "Missing required parameter '" + ex.getParameterName() + "'"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return respond(Problems.of(ErrorCode.METHOD_NOT_ALLOWED, "HTTP method not allowed for this resource"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return respond(Problems.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex) {
        return respond(Problems.of(ErrorCode.NOT_FOUND, "Resource not found"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        auditDenied(request, "MISSING_SCOPE");
        return respond(Problems.of(ErrorCode.FORBIDDEN, "Missing required permission (scope) for this operation"));
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ProblemDetail> handleConcurrency(RuntimeException ex) {
        log.warn("Concurrency/integrity conflict: {}", ex.getClass().getSimpleName());
        return respond(Problems.of(ErrorCode.CONFLICT,
                "The resource was modified concurrently or the request conflicts with existing data; retry"));
    }

    /**
     * R2: deadlock losers and lock timeouts (Postgres 40P01 / 55P03, lock_timeout = 5s) are transient:
     * the client should simply retry. Raw JPA types are listed too, for EntityManager calls outside repositories.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, CannotAcquireLockException.class,
            PessimisticLockException.class, LockTimeoutException.class, OptimisticLockException.class})
    public ResponseEntity<ProblemDetail> handleLockConflict(Exception ex) {
        log.warn("Lock conflict: {}", ex.getClass().getSimpleName());
        return respond(Problems.of(ErrorCode.CONFLICT,
                "The resource is busy (lock timeout, deadlock or concurrent update); retry"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return respond(Problems.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred"));
    }

    /** M9: denied attempts are security signals (probing, over-reaching agents). Never let auditing break the reply. */
    private void auditDenied(HttpServletRequest request, String reason) {
        try {
            // R8: at most one row per caller per minute, with the number of denials it stands for.
            audit.recordThrottled(AuditService.callerBucket(request.getRemoteAddr()), AuditAction.ACCESS_DENIED,
                    "endpoint", null, request.getMethod() + " " + request.getRequestURI() + " reason=" + reason);
        } catch (RuntimeException e) {
            log.warn("Could not audit access denial: {}", e.getClass().getSimpleName());
        }
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", message == null ? "is invalid" : message);
    }

    private static ResponseEntity<ProblemDetail> validation(List<Map<String, String>> errors) {
        ProblemDetail pd = Problems.of(ErrorCode.VALIDATION_FAILED, "Request validation failed");
        pd.setProperty("errors", errors);
        return respond(pd);
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
