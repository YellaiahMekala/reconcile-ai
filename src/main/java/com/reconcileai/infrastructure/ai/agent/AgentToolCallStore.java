package com.reconcileai.infrastructure.ai.agent;

import com.reconcileai.infrastructure.ai.agent.AgentRunContext.ToolCallTrace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Writes the tool-call trace of an agent run (plain JDBC, like EvidenceContentStore; row-level security applies). */
@Component
public class AgentToolCallStore {

    private final JdbcTemplate jdbc;

    public AgentToolCallStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveAll(String tenantId, UUID jobId, List<ToolCallTrace> calls) {
        for (ToolCallTrace c : calls) {
            jdbc.update("""
                INSERT INTO agent_tool_call (id, tenant_id, job_id, seq, tool, arg_hash, result_chars, outcome, latency_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), tenantId, jobId, c.seq(), c.tool(), c.argHash(), c.resultChars(), c.outcome(), c.latencyMs());
        }
    }

    /** One stored tool call, as shown to a reviewer (no arguments, only their hash). */
    public record StoredToolCall(int seq, String tool, String outcome, int resultChars, long latencyMs) {}

    public List<StoredToolCall> find(String tenantId, UUID jobId) {
        return jdbc.query("""
            SELECT seq, tool, outcome, result_chars, latency_ms FROM agent_tool_call
             WHERE tenant_id = ? AND job_id = ? ORDER BY seq""",
            (rs, i) -> new StoredToolCall(rs.getInt("seq"), rs.getString("tool"), rs.getString("outcome"),
                rs.getInt("result_chars"), rs.getLong("latency_ms")),
            tenantId, jobId);
    }

    /** One hash over the whole trace; the audit chain stores this instead of every row. */
    public static String digest(List<ToolCallTrace> calls) {
        StringBuilder sb = new StringBuilder();
        for (ToolCallTrace c : calls) {
            sb.append(c.seq()).append('|').append(c.tool()).append('|').append(c.argHash()).append('|')
                .append(c.outcome()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
