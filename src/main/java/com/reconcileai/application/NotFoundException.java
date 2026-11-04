package com.reconcileai.application;

/** Generic 404 with a stable error code. Used for "absent OR other tenant's" resources alike (no existence oracle). */
public class NotFoundException extends RuntimeException {

    private final String code;

    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
