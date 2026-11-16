package com.reconcileai.infrastructure.gateway;

import com.reconcileai.application.gateway.GatewayCallLog;
import com.reconcileai.domain.GatewayCallRecord;
import com.reconcileai.domain.LlmCallRecord;
import com.reconcileai.infrastructure.persistence.GatewayCallRepository;
import com.reconcileai.infrastructure.persistence.LlmCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes one {@code gateway_call} row per attempt in its own short transaction (the adapter runs outside any
 * transaction, on the job thread, under the job's tenant scope). A successful attempt also writes the {@code llm_call}
 * row that the direct path's {@code TokenUsageAdvisor} would have written, so per-tenant cost reporting stays
 * truthful on the gateway route (model {@code ai-gateway}).
 * <p>
 * A failure to write the log never fails the call: the gateway ledger is the authority, ours is the index into it.
 */
public class JpaGatewayCallLog implements GatewayCallLog {

    private static final Logger log = LoggerFactory.getLogger(JpaGatewayCallLog.class);
    static final String MODEL = "ai-gateway";

    private final GatewayCallRepository calls;
    private final LlmCallRepository llmCalls;
    private final TransactionTemplate tx;

    public JpaGatewayCallLog(GatewayCallRepository calls, LlmCallRepository llmCalls, TransactionTemplate tx) {
        this.calls = calls;
        this.llmCalls = llmCalls;
        this.tx = tx;
    }

    @Override
    public void record(Attempt a) {
        try {
            tx.executeWithoutResult(s -> {
                calls.save(new GatewayCallRecord(a.tenantId(), a.jobId(), a.idempotencyKey(), a.attempt(),
                    a.outcome().name(), a.httpStatus(), safe(a.code(), 48), a.invocationId(), safe(a.gatewayStatus(), 32),
                    a.inputTokens(), a.outputTokens(), a.latencyMs()));
                if (a.outcome() == Outcome.SUCCESS) {
                    llmCalls.save(new LlmCallRecord(a.tenantId(), a.jobId(), MODEL, a.inputTokens(), a.outputTokens(),
                        a.latencyMs(), "OK"));
                }
            });
        } catch (RuntimeException e) {
            log.warn("gateway call log not written for job {} attempt {}: {}", a.jobId(), a.attempt(), e.toString());
        }
    }

    /**
     * Codes and statuses come from the gateway's response body. Anything that is not a plain upper-case code, or is
     * too long for the column, is stored as UNMAPPED, so the row (and with it the invocation id) is never lost.
     */
    static String safe(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max && value.matches("[A-Z0-9_]+") ? value : "UNMAPPED";
    }
}
