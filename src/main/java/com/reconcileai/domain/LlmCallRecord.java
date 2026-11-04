package com.reconcileai.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per model call: the ground truth for token spend per tenant and per investigation.
 * Written by TokenUsageAdvisor from the ChatResponse usage metadata (Spring AI reports usage on the
 * response; this stack does not need a LangChain4j-style ChatModelListener).
 */
@Entity
@Table(name = "llm_call")
public class LlmCallRecord {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "job_id", columnDefinition = "uuid")
    private UUID jobId;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LlmCallRecord() { // JPA
    }

    public LlmCallRecord(String tenantId, UUID jobId, String model, int promptTokens, int completionTokens,
                         long latencyMs, String outcome) {
        this.tenantId = tenantId;
        this.jobId = jobId;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = promptTokens + completionTokens;
        this.latencyMs = latencyMs;
        this.outcome = outcome;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getJobId() { return jobId; }
    public String getModel() { return model; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getTotalTokens() { return totalTokens; }
    public long getLatencyMs() { return latencyMs; }
    public String getOutcome() { return outcome; }
    public Instant getCreatedAt() { return createdAt; }
}
