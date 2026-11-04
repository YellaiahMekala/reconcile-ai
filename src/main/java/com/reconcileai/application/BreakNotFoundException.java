package com.reconcileai.application;

import java.util.UUID;

/**
 * Thrown when a break id does not exist OR belongs to another tenant. The two cases are
 * deliberately indistinguishable and both map to 404 BREAK_NOT_FOUND.
 */
public class BreakNotFoundException extends RuntimeException {
    public BreakNotFoundException(UUID id) {
        super("Break not found: " + id);
    }
}
