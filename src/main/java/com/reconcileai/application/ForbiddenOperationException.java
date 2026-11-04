package com.reconcileai.application;

/**
 * 403 for a business-rule refusal on a resource the caller IS allowed to see (e.g. maker approving own proposal,
 * L1 approver on an L2 task). Cross-tenant access never uses this: that is always 404.
 */
public class ForbiddenOperationException extends RuntimeException {

    private final String code;

    public ForbiddenOperationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
