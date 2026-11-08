package com.reconcileai.application.digest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.infrastructure.messaging.OutboxRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds one tenant's digest for one UTC day, entirely with SQL aggregates (no LLM).
 *
 * <h2>Where the numbers come from</h2>
 * <ul>
 *   <li><b>Activity</b> (created, routed, resolved, rejected, blocked self-approvals): counted from {@code audit_event}
 *       in the day window. The audit chain is the tamper-evident record, so the digest cannot disagree with it.</li>
 *   <li><b>Backlog</b> (open breaks by status, oldest open age): current state of {@code break_item}.</li>
 *   <li><b>AI</b> (jobs, failures by reason, model calls, tokens, cache hits, estimated cost): {@code investigation_job}
 *       and {@code llm_call} in the window.</li>
 *   <li><b>Root causes and routes</b>: {@code resolution_proposal} created in the window.</li>
 * </ul>
 *
 * <h2>Why no LLM-written summary?</h2>
 * A digest is read by managers who act on the numbers. A model can mis-state a number; a template cannot. The
 * narrative is therefore a fixed template over the same values stored in {@code summary}.
 *
 * Runs inside the caller's tenant scope; every query also filters by tenant explicitly (RLS is the second guard).
 */
@Service
public class DailyDigestService {

    public record Digest(String tenantId, LocalDate date, Instant generatedAt, Map<String, Object> summary, String narrative) {}

    private static final List<String> OPEN = List.of("OPEN", "EVIDENCE_PROCESSING", "READY_TO_INVESTIGATE", "INVESTIGATING",
        "PROPOSAL_CREATED", "PENDING_L1", "PENDING_L2", "MANUAL_REVIEW_REQUIRED", "FAILED_RETRYABLE", "REJECTED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OutboxRecorder outbox;
    private final double promptPrice;
    private final double completionPrice;

    public DailyDigestService(JdbcTemplate jdbc, ObjectMapper json, OutboxRecorder outbox,
                              @Value("${reconcile.ai.pricing.prompt-usd-per-million:0}") double promptPrice,
                              @Value("${reconcile.ai.pricing.completion-usd-per-million:0}") double completionPrice) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
        this.promptPrice = promptPrice;
        this.completionPrice = completionPrice;
    }

    @Transactional
    public Digest generate(String tenantId, LocalDate day) {
        Timestamp from = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp to = Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        Map<String, Object> s = new LinkedHashMap<>();
        Map<String, Long> activity = countBy("""
            SELECT event_type AS k, count(*) AS n FROM audit_event
             WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at < ? GROUP BY event_type""", tenantId, from, to);
        s.put("breaksCreated", activity.getOrDefault("BREAK_CREATED", 0L));
        s.put("investigationsRequested", activity.getOrDefault("INVESTIGATION_REQUESTED", 0L));
        s.put("proposalsRouted", activity.getOrDefault("PROPOSAL_ROUTED", 0L));
        s.put("resolved", activity.getOrDefault("RESOLUTION_COMMITTED", 0L));
        s.put("rejected", activity.getOrDefault("PROPOSAL_REJECTED", 0L));
        s.put("selfApprovalsBlocked", activity.getOrDefault("MAKER_CHECKER_BLOCKED", 0L));

        Map<String, Long> backlog = countBy("SELECT status AS k, count(*) AS n FROM break_item WHERE tenant_id = ? "
            + "AND status IN (" + String.join(",", OPEN.stream().map(x -> "'" + x + "'").toList()) + ") GROUP BY status", tenantId);
        s.put("openBacklog", backlog);
        s.put("openTotal", backlog.values().stream().mapToLong(Long::longValue).sum());
        Double oldestHours = jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (? - min(created_at))) / 3600 FROM break_item "
            + "WHERE tenant_id = ? AND status IN (" + String.join(",", OPEN.stream().map(x -> "'" + x + "'").toList()) + ")",
            Double.class, to, tenantId);
        s.put("oldestOpenAgeHours", oldestHours == null ? null : BigDecimal.valueOf(oldestHours).setScale(1, RoundingMode.HALF_UP));

        s.put("jobsByStatus", countBy("""
            SELECT status AS k, count(*) AS n FROM investigation_job
             WHERE tenant_id = ? AND finished_at >= ? AND finished_at < ? GROUP BY status""", tenantId, from, to));
        s.put("failureReasons", countBy("""
            SELECT failure_reason AS k, count(*) AS n FROM investigation_job
             WHERE tenant_id = ? AND status = 'FAILED' AND finished_at >= ? AND finished_at < ?
             GROUP BY failure_reason ORDER BY n DESC LIMIT 5""", tenantId, from, to));
        s.put("rootCauses", countBy("""
            SELECT root_cause AS k, count(*) AS n FROM resolution_proposal
             WHERE tenant_id = ? AND created_at >= ? AND created_at < ? GROUP BY root_cause ORDER BY n DESC""", tenantId, from, to));
        s.put("routes", countBy("""
            SELECT route AS k, count(*) AS n FROM resolution_proposal
             WHERE tenant_id = ? AND created_at >= ? AND created_at < ? GROUP BY route""", tenantId, from, to));

        Map<String, Object> llm = jdbc.queryForMap("""
            SELECT count(*) FILTER (WHERE outcome = 'OK')        AS model_calls,
                   count(*) FILTER (WHERE outcome = 'CACHE_HIT') AS cache_hits,
                   count(*) FILTER (WHERE outcome = 'BLOCKED')   AS blocked,
                   count(*) FILTER (WHERE outcome = 'ERROR')     AS errors,
                   coalesce(sum(prompt_tokens), 0)               AS prompt_tokens,
                   coalesce(sum(completion_tokens), 0)           AS completion_tokens
              FROM llm_call WHERE tenant_id = ? AND created_at >= ? AND created_at < ?""", tenantId, from, to);
        long promptTokens = ((Number) llm.get("prompt_tokens")).longValue();
        long completionTokens = ((Number) llm.get("completion_tokens")).longValue();
        s.put("modelCalls", ((Number) llm.get("model_calls")).longValue());
        s.put("cacheHits", ((Number) llm.get("cache_hits")).longValue());
        s.put("blockedPrompts", ((Number) llm.get("blocked")).longValue());
        s.put("modelErrors", ((Number) llm.get("errors")).longValue());
        s.put("promptTokens", promptTokens);
        s.put("completionTokens", completionTokens);
        s.put("estimatedCostUsd", BigDecimal.valueOf((promptTokens * promptPrice + completionTokens * completionPrice) / 1_000_000d)
            .setScale(6, RoundingMode.HALF_UP));

        String narrative = narrative(day, s);
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO daily_digest (id, tenant_id, digest_date, generated_at, summary, narrative)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (tenant_id, digest_date)
            DO UPDATE SET generated_at = EXCLUDED.generated_at, summary = EXCLUDED.summary, narrative = EXCLUDED.narrative""",
            UUID.randomUUID(), tenantId, day, Timestamp.from(now), toJson(s), narrative);

        // Downstream (e-mail, Teams, a BI job) subscribes to the event; the app itself sends nothing.
        outbox.record(tenantId, "DIGEST", UUID.nameUUIDFromBytes((tenantId + "|" + day).getBytes()), "DAILY_DIGEST_READY",
            Map.of("digestDate", day.toString(), "openTotal", s.get("openTotal"), "resolved", s.get("resolved")));
        return new Digest(tenantId, day, now, s, narrative);
    }

    public Optional<Digest> find(String tenantId, LocalDate day) {
        return jdbc.query("SELECT digest_date, generated_at, summary::text AS summary, narrative FROM daily_digest "
                + "WHERE tenant_id = ? AND digest_date = ?",
            (rs, i) -> new Digest(tenantId, rs.getObject("digest_date", LocalDate.class),
                rs.getTimestamp("generated_at").toInstant(), fromJson(rs.getString("summary")), rs.getString("narrative")),
            tenantId, day).stream().findFirst();
    }

    public List<Digest> recent(String tenantId, int days) {
        return jdbc.query("SELECT digest_date, generated_at, summary::text AS summary, narrative FROM daily_digest "
                + "WHERE tenant_id = ? ORDER BY digest_date DESC LIMIT ?",
            (rs, i) -> new Digest(tenantId, rs.getObject("digest_date", LocalDate.class),
                rs.getTimestamp("generated_at").toInstant(), fromJson(rs.getString("summary")), rs.getString("narrative")),
            tenantId, days);
    }

    static String narrative(LocalDate day, Map<String, Object> s) {
        return String.format(java.util.Locale.ROOT,
            "Daily digest %s (UTC). Activity: %s breaks created, %s proposals routed, %s resolved, %s rejected, "
                + "%s self-approval attempts blocked. Backlog: %s open (oldest %s h). AI: %s model calls, %s cache hits, "
                + "%s blocked prompts, %s errors, %s + %s tokens, estimated USD %s.",
            day, s.get("breaksCreated"), s.get("proposalsRouted"), s.get("resolved"), s.get("rejected"),
            s.get("selfApprovalsBlocked"), s.get("openTotal"), s.get("oldestOpenAgeHours") == null ? "-" : s.get("oldestOpenAgeHours"),
            s.get("modelCalls"), s.get("cacheHits"), s.get("blockedPrompts"), s.get("modelErrors"),
            s.get("promptTokens"), s.get("completionTokens"), s.get("estimatedCostUsd"));
    }

    private Map<String, Long> countBy(String sql, Object... args) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            out.put(String.valueOf(rs.getString("k")), rs.getLong("n"));
        }, args);
        return out;
    }

    private String toJson(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String s) {
        try {
            return json.readValue(s, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
