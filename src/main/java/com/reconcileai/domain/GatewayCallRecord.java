package com.reconcileai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One HTTP attempt against ai-gateway (V13, platform Q2.4): the link {@code jobId -> idempotencyKey -> invocationId}
 * for reconciliation against the gateway ledger. Holds no prompt or answer text.
 */
@Entity
@Table(name = "gateway_call")
public class GatewayCallRecord {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 64)
    private String tenantId;

    @Column(name = "job_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID jobId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private int attempt;

    @Column(nullable = false, updatable = false, length = 16)
    private String outcome;

    @Column(name = "http_status", nullable = false, updatable = false)
    private int httpStatus;

    @Column(updatable = false, length = 48)
    private String code;

    @Column(name = "invocation_id", updatable = false, columnDefinition = "uuid")
    private UUID invocationId;

    @Column(name = "gateway_status", updatable = false, length = 32)
    private String gatewayStatus;

    @Column(name = "input_tokens", nullable = false, updatable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false, updatable = false)
    private int outputTokens;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GatewayCallRecord() { // JPA
    }

    @SuppressWarnings("java:S107")
    public GatewayCallRecord(String tenantId, UUID jobId, String idempotencyKey, int attempt, String outcome,
                             int httpStatus, String code, UUID invocationId, String gatewayStatus,
                             int inputTokens, int outputTokens, long latencyMs) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.jobId = jobId;
        this.idempotencyKey = idempotencyKey;
        this.attempt = attempt;
        this.outcome = outcome;
        this.httpStatus = httpStatus;
        this.code = code == null ? null : code.substring(0, Math.min(48, code.length()));
        this.invocationId = invocationId;
        this.gatewayStatus = gatewayStatus == null ? null : gatewayStatus.substring(0, Math.min(32, gatewayStatus.length()));
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = latencyMs;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getJobId() { return jobId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getAttempt() { return attempt; }
    public String getOutcome() { return outcome; }
    public int getHttpStatus() { return httpStatus; }
    public String getCode() { return code; }
    public UUID getInvocationId() { return invocationId; }
    public String getGatewayStatus() { return gatewayStatus; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public long getLatencyMs() { return latencyMs; }
    public Instant getCreatedAt() { return createdAt; }
}
