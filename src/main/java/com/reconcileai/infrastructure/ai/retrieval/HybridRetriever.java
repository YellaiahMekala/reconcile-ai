package com.reconcileai.infrastructure.ai.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.infrastructure.ai.ingestion.EvidenceIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-scoped HYBRID retrieval over one break's evidence: vector similarity + BM25-style keyword search,
 * fused with Reciprocal Rank Fusion.
 *
 * <pre>
 *                ┌─ vector leg:  VectorStore.similaritySearch(query, topK, floor,
 *   query ───────┤                 filter: tenantId == T && breakItemId == B && injectionSuspect == false)
 *                └─ keyword leg: SELECT ... WHERE content_tsv @@ to_tsquery(terms)
 *                                  AND metadata->>'tenantId' = T AND metadata->>'breakItemId' = B ...
 *                          ──► RRF fuse ──► top maxResults
 * </pre>
 *
 * <h2>Why hybrid?</h2>
 * Reconciliation evidence is full of exact tokens (references like "INV-20931", amounts, SWIFT codes)
 * that embeddings blur, while wording varies ("value date moved" vs "settled next day") in ways keyword
 * search misses. Each leg covers the other's blind spot.
 *
 * <h2>Tenant isolation (security-critical)</h2>
 * <ul>
 *   <li>tenantId is a METHOD PARAMETER supplied by the service from TenantContext, never from the query text.</li>
 *   <li>The vector leg uses Spring AI's {@code filterExpression}, which PgVectorStore compiles into the SAME
 *       SQL statement as the similarity search. It is not a post-filter over a global top-K.
 *       A similaritySearch WITHOUT a filterExpression would search every tenant's vectors.</li>
 *   <li>The keyword leg binds tenant/break as JDBC parameters (no string concatenation).</li>
 *   <li>Defence in depth: every returned row's metadata is re-checked and mismatches are dropped and logged.</li>
 * </ul>
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);
    private static final int MAX_KEYWORD_TERMS = 32;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RetrievalProperties props;

    public HybridRetriever(VectorStore vectorStore, JdbcTemplate jdbc, ObjectMapper objectMapper,
                           RetrievalProperties props) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public List<RetrievedChunk> retrieve(String tenantId, UUID breakItemId, String query) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(breakItemId, "breakItemId");
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Map<String, Candidate> candidates = new HashMap<>();
        List<String> vectorRanking = vectorLeg(tenantId, breakItemId, query, candidates);
        List<String> keywordRanking = keywordLeg(tenantId, breakItemId, query, candidates);

        Map<String, Double> fused = RrfFusion.fuse(List.of(vectorRanking, keywordRanking), props.rrfK());

        return fused.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(props.maxResults())
            .map(e -> {
                Candidate c = candidates.get(e.getKey());
                Integer kwRank = keywordRanking.contains(e.getKey()) ? keywordRanking.indexOf(e.getKey()) + 1 : null;
                return new RetrievedChunk(e.getKey(), c.evidenceId(), c.chunkIndex(), c.fileName(), c.text(),
                    c.vectorScore(), kwRank, e.getValue());
            })
            .toList();
    }

    // ------------------------------------------------------------------ vector leg

    private List<String> vectorLeg(String tenantId, UUID breakItemId, String query, Map<String, Candidate> out) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filter = b.and(
            b.and(b.eq(EvidenceIngestor.TENANT_ID, tenantId), b.eq(EvidenceIngestor.BREAK_ITEM_ID, breakItemId.toString())),
            b.eq(EvidenceIngestor.INJECTION_SUSPECT, false)
        ).build();

        SearchRequest request = SearchRequest.builder()
            .query(query)
            .topK(props.vectorTopK())
            .similarityThreshold(props.similarityThreshold()) // score floor: drops weakly related noise
            .filterExpression(filter)
            .build();

        List<String> ranking = new ArrayList<>();
        for (Document d : vectorStore.similaritySearch(request)) {
            Map<String, Object> md = d.getMetadata();
            if (!isOwned(md, tenantId, breakItemId)) {
                continue;
            }
            ranking.add(d.getId());
            out.putIfAbsent(d.getId(), Candidate.from(d.getId(), d.getText(), md, d.getScore()));
        }
        return ranking;
    }

    // ------------------------------------------------------------------ keyword leg

    private List<String> keywordLeg(String tenantId, UUID breakItemId, String query, Map<String, Candidate> out) {
        String tsQuery = toOrTsQuery(query);
        if (tsQuery.isEmpty()) {
            return List.of();
        }
        List<String> ranking = new ArrayList<>();
        jdbc.query("""
                SELECT id::text AS id, content, metadata::text AS metadata,
                       ts_rank_cd(content_tsv, to_tsquery('english', ?)) AS rank
                  FROM vector_store
                 WHERE content_tsv @@ to_tsquery('english', ?)
                   AND metadata->>'tenantId' = ?
                   AND metadata->>'breakItemId' = ?
                   AND coalesce(metadata->>'injectionSuspect', 'false') = 'false'
                 ORDER BY rank DESC
                 LIMIT ?""",
            rs -> {
                String id = rs.getString("id");
                Map<String, Object> md = parseMetadata(rs.getString("metadata"));
                if (isOwned(md, tenantId, breakItemId)) {
                    ranking.add(id);
                    out.putIfAbsent(id, Candidate.from(id, rs.getString("content"), md, null));
                }
            },
            tsQuery, tsQuery, tenantId, breakItemId.toString(), props.keywordTopK());
        return ranking;
    }

    /**
     * Builds an OR tsquery ("inv20931 | settled | fee") from free text. Only [a-z0-9] tokens of length >= 3
     * survive, so user text can never inject tsquery operators. OR (not AND) because investigation queries
     * are long and an AND of every word would almost never match.
     */
    static String toOrTsQuery(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() >= 3) {
                terms.add(raw);
            }
            if (terms.size() == MAX_KEYWORD_TERMS) {
                break;
            }
        }
        return String.join(" | ", terms);
    }

    // ------------------------------------------------------------------ helpers

    private boolean isOwned(Map<String, Object> md, String tenantId, UUID breakItemId) {
        boolean owned = tenantId.equals(String.valueOf(md.get(EvidenceIngestor.TENANT_ID)))
            && breakItemId.toString().equals(String.valueOf(md.get(EvidenceIngestor.BREAK_ITEM_ID)));
        if (!owned) {
            // Should be impossible given the query predicates. If it ever happens, it is a security bug: log loudly.
            log.error("SECURITY: retrieval returned a chunk outside tenant/break scope; dropped. expectedTenant={}", tenantId);
        }
        return owned;
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private record Candidate(String id, UUID evidenceId, int chunkIndex, String fileName, String text, Double vectorScore) {
        static Candidate from(String id, String text, Map<String, Object> md, Double score) {
            Object idx = md.get(EvidenceIngestor.CHUNK_INDEX);
            return new Candidate(id,
                UUID.fromString(String.valueOf(md.get(EvidenceIngestor.EVIDENCE_ID))),
                idx instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(idx)),
                String.valueOf(md.get(EvidenceIngestor.FILE_NAME)),
                text,
                score);
        }
    }
}
