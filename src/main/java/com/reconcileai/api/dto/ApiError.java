package com.reconcileai.api.dto;

import java.time.Instant;

/**
 * Uniform error body: a stable machine-readable {@code code} (clients branch on this) plus a
 * human {@code message}. Messages never contain stack traces, SQL, or other tenants' data.
 */
public record ApiError(String code, String message, Instant timestamp) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}
