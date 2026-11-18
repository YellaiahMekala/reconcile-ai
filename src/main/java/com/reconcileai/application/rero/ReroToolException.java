package com.reconcileai.application.rero;

/** A refused internal tool call (Contract C section 2). {@code code} and {@code message} are safe to return. */
public class ReroToolException extends RuntimeException {

    public static final String JOB_MISMATCH = "RUN_TOKEN_JOB_MISMATCH";
    public static final String JOB_NOT_RUNNING = "JOB_NOT_RUNNING";
    public static final String BUDGET_EXCEEDED = "TOOL_BUDGET_EXCEEDED";

    private final int status;
    private final String code;

    public ReroToolException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ReroToolException jobMismatch() {
        return new ReroToolException(403, JOB_MISMATCH, "The run token does not grant access to this job");
    }

    public static ReroToolException notRunning() {
        return new ReroToolException(409, JOB_NOT_RUNNING, "The job is not a running RERO investigation");
    }

    public static ReroToolException budgetExceeded() {
        return new ReroToolException(429, BUDGET_EXCEEDED, "The run's tool budget is used up");
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
