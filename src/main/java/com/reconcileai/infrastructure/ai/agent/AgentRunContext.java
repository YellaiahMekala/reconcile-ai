package com.reconcileai.infrastructure.ai.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everything one agent run is allowed to touch, fixed when the job starts. Tenant and break come from the job row
 * (which got them from the JWT), so no tool takes either as a parameter and a prompt-injected model cannot name
 * another tenant. The run also collects what happened: the chunk ids that tools returned (the only ids a proposal may
 * cite) and a trace of every tool call.
 */
public final class AgentRunContext {

    /** One tool call, as written to agent_tool_call. */
    public record ToolCallTrace(int seq, String tool, String argHash, int resultChars, String outcome, long latencyMs) {}

    private final String tenantId;
    private final UUID breakId;
    private final UUID jobId;
    private final Set<String> roles;
    private final Instant deadline;
    private final int maxToolCalls;
    private final int maxModelTurns;

    private final Set<String> retrievedChunkIds = new LinkedHashSet<>();
    private final List<ToolCallTrace> trace = new ArrayList<>();
    private int toolCalls;
    private int modelTurns;

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

    /** Next tool call number (1-based). A model that ignores the limit message for too long is stopped outright. */
    public synchronized int nextToolCall() {
        toolCalls++;
        if (toolCalls > maxToolCalls + 2) {
            throw new AgentLimitException(AgentLimitException.TOOL_LOOP);
        }
        return toolCalls;
    }

    public synchronized void addRetrieved(String chunkId) {
        retrievedChunkIds.add(chunkId);
    }

    public synchronized List<String> retrievedChunkIds() {
        return List.copyOf(retrievedChunkIds);
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
