package com.reconcileai.application;

/**
 * The caller's tenant already has a break with this reference. Maps to 409.
 * References are unique per tenant, not globally.
 */
public class DuplicateBreakReferenceException extends RuntimeException {
    public DuplicateBreakReferenceException(String ref) {
        super("Break reference already exists for tenant: " + ref);
    }
}
