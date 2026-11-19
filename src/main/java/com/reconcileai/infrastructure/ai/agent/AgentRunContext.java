// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Everything one agent run is allowed to touch, fixed when the job starts. Tenant and break come from the job row
 * (which got them from the JWT), so no tool takes either as a parameter and a prompt-injected model cannot name
 * another tenant. The run also collects what happened: the chunk ids that tools returned (the only ids a proposal may
 * cite), the text of those chunks as the model saw it (the reviewer checks quotes against it) and a trace of every
 * tool call.
 *
 * <p>In supervised mode several specialist agents share ONE context, so they share one budget: adding agents cannot
 * multiply the tool-call, model-turn or time limits. {@link #asAgent} labels the calls each specialist makes.
 */
public final class AgentRunContext {

    /** One tool call (or, in supervised mode, one delegation to a specialist), as written to agent_tool_call. */
    public record ToolCallTrace(int seq, String tool, String argHash, int resultChars, String outcome, long latencyMs,
                                String agentName) {
        public ToolCallTrace(int seq, String tool, String argHash, int resultChars, String outcome, long latencyMs) {
            this(seq, tool, argHash, resultChars, outcome, latencyMs, null);
        }
    }

    private final String tenantId;
    private final UUID breakId;
    private final UUID jobId;
    private final Set<String> roles;
    private final Instant deadline;
    private final int maxToolCalls;
    private final int maxModelTurns;

    private final Map<String, String> retrievedChunks = new LinkedHashMap<>();
    private final List<ToolCallTrace> trace = new ArrayList<>();
    private int toolCalls;
    private int traceSeq;
    private int modelTurns;
    private String currentAgent;

    public AgentRunContext(String tenantId, UUID breakId, UUID jobId, Set<String> roles, Duration timeout,
                           int maxToolCalls, int maxModelTurns) {
        this.tenantId = tenantId;
        this.breakId = breakId;
        this.jobId = jobId;
        this.roles = Set.copyOf(roles);
        this.deadline = Instant.now().plus(timeout);
        this.maxToolCalls = maxToolCalls;
        this.maxModelTurns = maxModelTurns;
    }

    public String tenantId() { return tenantId; }
    public UUID breakId() { return breakId; }
    public UUID jobId() { return jobId; }
    public int maxToolCalls() { return maxToolCalls; }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public void checkDeadline() {
        if (Instant.now().isAfter(deadline)) {
            throw new AgentLimitException(AgentLimitException.TIMEOUT);
        }
    }

    /** Called once per model invocation; the run fails when the model keeps going past its turn budget. */
    public synchronized void startModelTurn() {
        checkDeadline();
        if (++modelTurns > maxModelTurns) {
            throw new AgentLimitException(AgentLimitException.TURN_LIMIT);
        }
    }

    /**
     * Counts one tool call against the run budget and returns the count (1-based). A model that ignores the limit
     * message for too long is stopped outright.
     */
    public synchronized int nextToolCall() {
        toolCalls++;
        if (toolCalls > maxToolCalls + 2) {
            throw new AgentLimitException(AgentLimitException.TOOL_LOOP);
        }
        return toolCalls;
    }

    /** Next row number in the trace. Tool calls and supervisor delegations share this sequence. */
    public synchronized int nextSeq() {
        return ++traceSeq;
    }

    /** Runs {@code body} with every tool call it makes labelled as {@code agentName}; restores the previous label. */
    public <T> T asAgent(String agentName, Supplier<T> body) {
        String previous;
        synchronized (this) {
            previous = currentAgent;
            currentAgent = agentName;
        }
        try {
            return body.get();
        } finally {
            synchronized (this) {
                currentAgent = previous;
            }
        }
    }

    public synchronized String currentAgent() {
        return currentAgent;
    }

    public synchronized void addRetrieved(String chunkId) {
        retrievedChunks.putIfAbsent(chunkId, null);
    }

    /** @param shownText the chunk text exactly as the model was shown it (after PII masking) */
    public synchronized void addRetrieved(String chunkId, String shownText) {
        retrievedChunks.put(chunkId, shownText);
    }

    public synchronized List<String> retrievedChunkIds() {
        return List.copyOf(retrievedChunks.keySet());
    }

    /** Text of a chunk a search tool returned in this run; null when the id was never retrieved here. */
    public synchronized String chunkText(String chunkId) {
        return retrievedChunks.get(chunkId);
    }

    public synchronized void record(ToolCallTrace call) {
        trace.add(call);
    }

    public synchronized List<ToolCallTrace> trace() {
        return Collections.unmodifiableList(new ArrayList<>(trace));
    }

    public synchronized int modelTurns() {
        return modelTurns;
    }
}
