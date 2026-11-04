package com.reconcileai.application;

/** Generic 409 with a stable, client-branchable error code (e.g. EVIDENCE_NOT_READY). */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
