package com.reconcileai.application.gateway;

import java.time.Instant;
import java.util.UUID;

/**
 * A governed chat completion through ai-gateway (platform Q2.1). One logical call per investigation step; the
 * idempotency key is fixed before the first attempt and reused for any transport retry.
 * <p>
 * Contract: ai-gateway {@code docs/integration/CONSUMER_CONTRACT.md} and schemas {@code chat-request.v1.json},
 * {@code chat-response.v1.json}, {@code error.v1.json} (copied under {@code src/test/resources/contracts/ai-gateway}).
 */
public interface GatewayChatPort {

    Reply chat(Request request);

    /**
     * @param tenantId       the job's tenant; only used to label our side of the ledger, never sent to the gateway
     *                       (the gateway takes the tenant from the service token, and rejects any override)
     * @param jobId          the investigation job, for the ledger link {@code jobId -> idempotencyKey -> invocationId}
     * @param idempotencyKey from {@link IdempotencyKeys#forStep}; never generated per attempt
     * @param deadline       the caller's own deadline; a retry is attempted only if it can finish before it
     */
    record Request(String tenantId, UUID jobId, String idempotencyKey, String system, String user,
                   int maxOutputTokens, double temperature, Instant deadline) {
        public Request {
            IdempotencyKeys.requireValid(idempotencyKey);
        }

        @Override
        public String toString() {
            return "Request[jobId=" + jobId + ", idempotencyKey=" + idempotencyKey + ", text=<redacted>]";
        }
    }

    /** {@code invocationId} is the gateway ledger row; keep it with the job for reconciliation. */
    record Reply(UUID invocationId, String status, String output, int inputTokens, int outputTokens) {
        @Override
        public String toString() {
            return "Reply[invocationId=" + invocationId + ", status=" + status + ", output=<redacted>]";
        }
    }
}
