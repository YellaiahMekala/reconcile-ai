// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.api;

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
import org.springframework.http.ProblemDetail;
import com.reconcileai.infrastructure.web.Problems;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps application exceptions to RFC 7807 {@code application/problem+json} ({@link Problems}): type, title, status,
 * detail, instance, plus the stable {@code code} extension (values and HTTP statuses unchanged from the earlier
 * {@code ApiError} body, whose {@code message} and {@code timestamp} fields are kept as extensions).
 *
 * <p>DELIBERATELY ABSENT: a catch-all {@code @ExceptionHandler(Exception.class)}. A catch-all would
 * also swallow Spring Security's {@code AccessDeniedException} thrown by {@code @PreAuthorize},
 * turning correct 403s into 500s. 401/403 are produced by the security filter chain instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BreakNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(BreakNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.NOT_FOUND, "BREAK_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(DuplicateBreakReferenceException.class)
    public ResponseEntity<ProblemDetail> duplicate(DuplicateBreakReferenceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.CONFLICT, "DUPLICATE_BREAK_REFERENCE", e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> genericNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.NOT_FOUND, e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> genericConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.CONFLICT, e.getCode(), e.getMessage()));
    }

    /** Business-rule refusal on a visible resource (maker-checker, approval tier). Cross-tenant stays 404. */
    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ProblemDetail> forbidden(ForbiddenOperationException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.FORBIDDEN, e.getCode(), e.getMessage()));
    }

    /** Two checkers deciding the same task at once: the loser gets 409 and re-reads the task. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> optimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "The resource was changed by another request; reload and retry"));
    }

    /** Malformed JSON or an unknown enum value (e.g. decision "MAYBE"): 400, not 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is not valid"));
    }

    @ExceptionHandler(InputRejectedException.class)
    public ResponseEntity<ProblemDetail> inputRejected(InputRejectedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.BAD_REQUEST, "INPUT_REJECTED", e.getMessage()));
    }

    /** Back-pressure: tell clients to retry later instead of queueing unboundedly. */
    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<ProblemDetail> capacity(CapacityExceededException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "30")
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.SERVICE_UNAVAILABLE, "CAPACITY_EXCEEDED", e.getMessage()));
    }

    /** A reconcile named a gateway invocation id and the gateway ledger could not be read: nothing was recorded. */
    @ExceptionHandler(com.reconcileai.application.gateway.GatewayLedgerUnavailableException.class)
    public ResponseEntity<ProblemDetail> ledgerUnavailable(com.reconcileai.application.gateway.GatewayLedgerUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "30")
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.SERVICE_UNAVAILABLE, "GATEWAY_LEDGER_UNAVAILABLE", e.getMessage()));
    }

    /** Per-tenant throttle on model-triggering requests. */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", e.getMessage()));
    }

    /** Lifecycle conflicts (e.g. uploading evidence while PENDING_L1). */
    @ExceptionHandler(IllegalBreakStateException.class)
    public ResponseEntity<ProblemDetail> illegalState(IllegalBreakStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.CONFLICT, "ILLEGAL_BREAK_STATE", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> illegalArg(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage()));
    }

    /**
     * Unique-constraint race: two concurrent requests passed the exists-check and the second insert
     * hit uq_break_tenant_reference or uq_evidence_dedup. Report as a conflict, not a server error.
     * The DB message is not echoed back (it can contain schema details).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem(HttpStatus.CONFLICT, "CONFLICT", "Request conflicts with existing data; retry or use a different reference"));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String message) {
        String instance = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            instance = attrs.getRequest().getRequestURI();
        }
        return Problems.detail(status, code, message, instance);
    }
}
