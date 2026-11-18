package com.reconcileai.infrastructure.rero;

import com.reconcileai.application.rero.ReroRunStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Plain JDBC (like AgentToolCallStore): the budget must be one conditional statement, not read-modify-write through
 * JPA. Every statement names the tenant, and Row-Level Security applies on top (the connection is bound to the
 * caller's tenant by TenantAwareDataSource).
 */
@Component
public class JdbcReroRunStore implements ReroRunStore {

    /** Tool calls carry no stored arguments; the column is NOT NULL, so it holds the hash of the empty string. */
    static final String NO_ARGUMENTS_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String AGENT_NAME = "rero";

    private final JdbcTemplate jdbc;

    public JdbcReroRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OptionalInt tryConsumeToolCall(String tenantId, UUID jobId) {
        List<Integer> updated = jdbc.queryForList("""
            UPDATE investigation_job
               SET tool_calls_used = tool_calls_used + 1
             WHERE id = ? AND tenant_id = ? AND mode = 'RERO' AND status = 'RUNNING'
               AND tool_calls_max IS NOT NULL AND tool_calls_used < tool_calls_max
            RETURNING tool_calls_used""", Integer.class, jobId, tenantId);
        return updated.isEmpty() ? OptionalInt.empty() : OptionalInt.of(updated.get(0));
    }

    @Override
    public void recordServedChunks(String tenantId, UUID jobId, Collection<String> chunkIds) {
        for (String chunkId : chunkIds) {
            jdbc.update("""
                INSERT INTO rero_served_chunk (tenant_id, job_id, chunk_id) VALUES (?, ?, ?)
                ON CONFLICT (job_id, chunk_id) DO NOTHING""", tenantId, jobId, chunkId);
        }
    }

    @Override
    public List<String> servedChunkIds(String tenantId, UUID jobId) {
        return jdbc.queryForList("SELECT chunk_id FROM rero_served_chunk WHERE tenant_id = ? AND job_id = ? ORDER BY served_at",
            String.class, tenantId, jobId);
    }

    @Override
    public void recordToolCall(String tenantId, UUID jobId, int seq, String tool, int resultChars, String outcome,
                               long latencyMs) {
        jdbc.update("""
            INSERT INTO agent_tool_call (id, tenant_id, job_id, seq, tool, arg_hash, result_chars, outcome, latency_ms,
                                         agent_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            UUID.randomUUID(), tenantId, jobId, seq, tool, NO_ARGUMENTS_HASH, resultChars, outcome, latencyMs, AGENT_NAME);
    }
}
