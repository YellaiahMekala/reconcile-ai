package com.reconcileai.application.rero;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * RERO's recommendation API (Contract C section 1). The tenant is never in a body: the adapter picks the tenant's own
 * service token (audiences {@code rero-api} and {@code ai-gateway}), and RERO derives the tenant from it.
 * Every failure is a {@link ReroCallException} with a short code; nothing is retried here.
 */
public interface ReroClientPort {

    /** {@code POST /v1/recommendations} with {@code Idempotency-Key: jobId}. 202 (new) and 200 (same run) both return the run. */
    ReroRun start(String tenantId, StartRequest request);

    /** {@code GET /v1/recommendations/{runId}}. */
    ReroRun get(String tenantId, UUID runId);

    record Limits(int maxToolCalls, int maxModelCalls, int deadlineSeconds) { }

    /** {@code runToken} is excluded from {@link #toString()} so it can never reach a log line. */
    record StartRequest(UUID jobId, String analystNote, Limits limits, String runToken) {
        @Override
        public String toString() {
            return "StartRequest[jobId=" + jobId + ", limits=" + limits + "]";
        }
    }

    enum Status { RUNNING, SUCCEEDED, FAILED }

    enum Stage { LOAD_CONTEXT, RETRIEVE, DRAFT, SELF_CHECK, DONE }

    enum SelfCheck { PASS, CHALLENGE }

    record Result(String rootCauseCategory, String explanation, List<String> citedChunkIds, BigDecimal confidence,
                  SelfCheck selfCheck, String selfCheckReason) {
        public Result {
            citedChunkIds = citedChunkIds == null ? List.of() : List.copyOf(citedChunkIds);
        }
    }

    record ReroRun(UUID runId, UUID jobId, Status status, Stage stage, String failureCode, int toolCallsUsed,
                   int modelCallsUsed, List<String> gatewayInvocationIds, Result result) {
        public ReroRun {
            gatewayInvocationIds = gatewayInvocationIds == null ? List.of() : List.copyOf(gatewayInvocationIds);
        }

        public boolean terminal() {
            return status == Status.SUCCEEDED || status == Status.FAILED;
        }
    }
}
