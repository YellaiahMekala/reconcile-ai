package com.reconcileai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.BreakSource;
import com.reconcileai.domain.Money;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder;
import com.reconcileai.infrastructure.ai.retrieval.HybridRetriever;
import com.reconcileai.infrastructure.ai.retrieval.HybridRetriever.Leg;
import com.reconcileai.infrastructure.security.TenantScope;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures retrieval on its own, per leg. Each case gets its own break holding the case's evidence plus five
 * distractor files taken from cases with a different root cause, so the retriever has to rank the right file above
 * plausible but wrong ones. The query is the one the real investigation builds, not a hand-tuned one.
 *
 * <p>Embeddings here are the test hashing model (word overlap), so the dense numbers are a floor for the pipeline,
 * not a statement about OpenAI embeddings. The floors below pin today's behaviour so a retrieval change that makes
 * ranking worse fails the build.
 */
class RetrievalEvalIT extends AbstractPostgresIT {

    private static final int K = 5;
    private static final int DISTRACTORS = 5;

    @LocalServerPort
    int port;

    @Autowired
    HybridRetriever retriever;

    @Autowired
    InvestigationPromptBuilder promptBuilder;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    private record LegScore(double recallAtK, double mrr, double hitAt1) {}

    @Test
    void hybridRankingIsAtLeastAsGoodAsEitherSingleLeg() throws IOException {
        List<EvalCase> cases = new EvalCaseLoader().loadAll().stream()
            .filter(c -> !c.expected().mustCiteEvidence().isEmpty()).toList();
        String tenant = "TENANT_RETR_" + UUID.randomUUID().toString().substring(0, 8);
        String bearer = JwtTestSupport.bearer("retr-analyst", tenant, List.of("RECON_ANALYST"));

        Map<Leg, List<double[]>> perCase = new EnumMap<>(Leg.class);
        for (Leg leg : Leg.values()) {
            perCase.put(leg, new ArrayList<>());
        }

        for (EvalCase c : cases) {
            String breakId = ApiTestSupport.createBreak(bearer, c.ledgerAmount(), c.externalAmount(), c.currency());
            List<String> files = new ArrayList<>(c.evidence());
            distractorFiles(c, cases).forEach(files::add);
            files.forEach(f -> ApiTestSupport.upload(bearer, breakId, EvalCaseLoader.EVIDENCE_DIR + f, "text/plain"));

            Map<String, String> fileByChunk = adminJdbc().query(
                "SELECT id::text AS id, metadata->>'fileName' AS file FROM vector_store WHERE metadata->>'breakItemId' = ?",
                rs -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    while (rs.next()) {
                        m.put(rs.getString("id"), rs.getString("file"));
                    }
                    return m;
                }, breakId);
            Set<String> relevant = fileByChunk.entrySet().stream()
                .filter(e -> c.expected().mustCiteEvidence().contains(e.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());

            BreakItem probe = new BreakItem(tenant, "EVAL-" + c.id(), Money.of(c.ledgerAmount(), c.currency()),
                Money.of(c.externalAmount(), c.currency()), BreakSource.REST);
            String query = promptBuilder.retrievalQuery(probe, null);

            for (Leg leg : Leg.values()) {
                List<String> ranked = TenantScope.call(tenant,
                    () -> retriever.rankedChunkIds(tenant, UUID.fromString(breakId), query, leg));
                perCase.get(leg).add(score(ranked, relevant));
            }
        }

        Map<Leg, LegScore> scores = new EnumMap<>(Leg.class);
        perCase.forEach((leg, rows) -> scores.put(leg, new LegScore(
            rows.stream().mapToDouble(r -> r[0]).average().orElse(0),
            rows.stream().mapToDouble(r -> r[1]).average().orElse(0),
            rows.stream().mapToDouble(r -> r[2]).average().orElse(0))));
        write(cases.size(), scores);

        LegScore dense = scores.get(Leg.DENSE);
        LegScore sparse = scores.get(Leg.SPARSE);
        LegScore hybrid = scores.get(Leg.HYBRID);
        assertThat(hybrid.mrr()).as("hybrid MRR").isGreaterThanOrEqualTo(Math.max(dense.mrr(), sparse.mrr()));
        assertThat(hybrid.recallAtK()).as("hybrid recall@" + K).isGreaterThanOrEqualTo(Math.max(dense.recallAtK(), sparse.recallAtK()));
    }

    /** The next few cases (wrapping around) whose root cause differs, so a distractor never shares the right answer. */
    private static List<String> distractorFiles(EvalCase c, List<EvalCase> all) {
        List<String> out = new ArrayList<>();
        int start = all.indexOf(c);
        for (int i = 1; i < all.size() && out.size() < DISTRACTORS; i++) {
            EvalCase other = all.get((start + i) % all.size());
            if (!other.expected().rootCause().equals(c.expected().rootCause())) {
                out.add(other.evidence().get(0));
            }
        }
        return out;
    }

    private static double[] score(List<String> ranked, Set<String> relevant) {
        List<String> top = ranked.stream().limit(K).toList();
        long found = top.stream().filter(relevant::contains).count();
        double recall = relevant.isEmpty() ? 0 : (double) found / relevant.size();
        double mrr = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }
        double hit1 = !ranked.isEmpty() && relevant.contains(ranked.get(0)) ? 1 : 0;
        return new double[] {recall, mrr, hit1};
    }

    private void write(int cases, Map<Leg, LegScore> scores) throws IOException {
        Files.createDirectories(EvalHarness.REPORT_DIR);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .writeValue(EvalHarness.REPORT_DIR.resolve("retrieval-mock-llm.json").toFile(), scores);
        StringBuilder md = new StringBuilder("# Retrieval eval (test hashing embeddings)\n\n")
            .append(cases).append(" cases, each with ").append(DISTRACTORS)
            .append(" distractor files from other root causes in the same break. Query = the real investigation query.\n\n")
            .append("| Leg | recall@").append(K).append(" | MRR | hit@1 |\n|---|---|---|---|\n");
        scores.forEach((leg, s) -> md.append(String.format("| %s | %.2f | %.2f | %.2f |%n", leg, s.recallAtK(), s.mrr(), s.hitAt1())));
        Files.writeString(EvalHarness.REPORT_DIR.resolve("retrieval-mock-llm.md"), md);
        System.out.println("[eval] retrieval\n" + md);
    }
}
