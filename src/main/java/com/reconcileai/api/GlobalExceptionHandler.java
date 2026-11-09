package com.reconcileai.api;

import com.reconcileai.api.dto.ApiError;
import com.reconcileai.application.BreakNotFoundException;
import com.reconcileai.application.CapacityExceededException;
import com.reconcileai.application.ConflictException;
import com.reconcileai.application.DuplicateBreakReferenceException;
import com.reconcileai.application.ForbiddenOperationException;
import com.reconcileai.application.InputRejectedException;
import com.reconcileai.application.RateLimitExceededException;
import com.reconcileai.application.NotFoundException;
import com.reconcileai.domain.IllegalBreakStateException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps application exceptions to a consistent {@link ApiError} JSON body.
 *
 * <p>DELIBERATELY ABSENT: a catch-all {@code @ExceptionHandler(Exception.class)}. A catch-all would
 * also swallow Spring Security's {@code AccessDeniedException} thrown by {@code @PreAuthorize},
 * turning correct 403s into 500s. 401/403 are produced by the security filter chain instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BreakNotFoundException.class)
    public ResponseEntity<ApiError> notFound(BreakNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError.of("BREAK_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(DuplicateBreakReferenceException.class)
    public ResponseEntity<ApiError> duplicate(DuplicateBreakReferenceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of("DUPLICATE_BREAK_REFERENCE", e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> genericNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(ApiError.of(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> genericConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(e.getCode(), e.getMessage()));
    }

    /** Business-rule refusal on a visible resource (maker-checker, approval tier). Cross-tenant stays 404. */
    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenOperationException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(e.getCode(), e.getMessage()));
    }

    /** Two checkers deciding the same task at once: the loser gets 409 and re-reads the task. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> optimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of("CONCURRENT_MODIFICATION", "The resource was changed by another request; reload and retry"));
    }

    /** Malformed JSON or an unknown enum value (e.g. decision "MAYBE"): 400, not 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiError.of("MALFORMED_REQUEST", "Request body is not valid"));
    }

    @ExceptionHandler(InputRejectedException.class)
    public ResponseEntity<ApiError> inputRejected(InputRejectedException e) {
        return ResponseEntity.badRequest().body(ApiError.of("INPUT_REJECTED", e.getMessage()));
    }

    /** Back-pressure: tell clients to retry later instead of queueing unboundedly. */
    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<ApiError> capacity(CapacityExceededException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "30")
            .body(ApiError.of("CAPACITY_EXCEEDED", e.getMessage()));
    }

    /** Per-tenant throttle on model-triggering requests. */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> rateLimited(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
            .body(ApiError.of("RATE_LIMITED", e.getMessage()));
    }

    /** Lifecycle conflicts (e.g. uploading evidence while PENDING_L1). */
    @ExceptionHandler(IllegalBreakStateException.class)
    public ResponseEntity<ApiError> illegalState(IllegalBreakStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of("ILLEGAL_BREAK_STATE", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
            .body(ApiError.of("VALIDATION_FAILED", detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegalArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
            .body(ApiError.of("BAD_REQUEST", e.getMessage()));
    }

    /**
     * Unique-constraint race: two concurrent requests passed the exists-check and the second insert
     * hit uq_break_tenant_reference or uq_evidence_dedup. Report as a conflict, not a server error.
     * The DB message is not echoed back (it can contain schema details).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of("CONFLICT", "Request conflicts with existing data; retry or use a different reference"));
    }
}
