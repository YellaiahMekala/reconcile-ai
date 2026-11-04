package com.reconcileai.domain;

import java.util.UUID;

/**
 * An operation is not allowed in the break's current lifecycle state (mapped to HTTP 409).
 * Lives in the domain because the transition rules live on {@link BreakItem}.
 */
public class IllegalBreakStateException extends RuntimeException {

    private final BreakStatus status;

    public IllegalBreakStateException(UUID breakId, BreakStatus status, String operation) {
        super("Break %s is %s and cannot %s".formatted(breakId, status, operation));
        this.status = status;
    }

    public BreakStatus getStatus() {
        return status;
    }
}
