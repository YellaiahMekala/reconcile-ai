// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reconcileai.application.knowledge.KnowledgeHit;
import com.reconcileai.application.knowledge.KnowledgeResult;
import com.reconcileai.application.knowledge.KnowledgeSearchPort;
import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import com.reconcileai.infrastructure.ai.pii.PiiMasker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * {@link KnowledgeSearchPort} over EKA's edge (platform Q1.2 to Q1.4, TDD 4.2):
 * <pre>
 *  job tenant -> mask query -> service token for that tenant -> POST /api/v1/search (breaker, timeout)
 *            -> screen hits (injection scan, PII mask, truncate, budget) -> outcome + hits
 * </pre>
 * Only {@code POST /api/v1/search} is ever called; EKA chat and conversations are out of bounds (architecture test).
 * <h2>Error mapping</h2>
 * <table>
 *   <tr><td>200 with results</td><td>OK, or REJECTED {@code ALL_HITS_SCREENED} if screening dropped every hit</td></tr>
 *   <tr><td>200 without results</td><td>EMPTY</td></tr>
 *   <tr><td>401 / 403</td><td>UNAVAILABLE {@code AUTH}: a configuration fault, never retried</td></tr>
 *   <tr><td>422 / 400</td><td>REJECTED {@code QUERY_INVALID}: our request was refused, retrying will not help</td></tr>
 *   <tr><td>429</td><td>UNAVAILABLE {@code RATE_LIMITED}</td></tr>
 *   <tr><td>5xx, other 4xx, bad body</td><td>UNAVAILABLE {@code HTTP_nnn} / {@code BAD_RESPONSE}</td></tr>
 *   <tr><td>timeout, network</td><td>UNAVAILABLE {@code TIMEOUT} / {@code NETWORK}</td></tr>
 *   <tr><td>breaker open</td><td>UNAVAILABLE {@code CIRCUIT_OPEN}, no request sent</td></tr>
 *   <tr><td>token failure</td><td>UNAVAILABLE with the token code ({@code TENANT_CREDENTIALS_MISSING}, ...)</td></tr>
 * </table>
 * There is no retry: knowledge is advisory, and the investigation should not wait on it. Logs carry outcome codes,
 * counts and screening rule names, never the query, a passage or a token.
 */
public class EkaKnowledgeClient implements KnowledgeSearchPort {

    static final String SEARCH_PATH = "/api/v1/search";
    private static final int EKA_MAX_QUERY_CHARS = 1000;
    private static final Logger log = LoggerFactory.getLogger(EkaKnowledgeClient.class);

    private final HttpClient http;
    private final ObjectMapper json;
    private final KnowledgeProperties props;
    private final ServiceTokenProvider tokens;
    private final InjectionScanner scanner;
    private final CircuitBreaker breaker;
    private final BiConsumer<String, String> outcomeCounter;

    /**
     * @param outcomeCounter called once per lookup with (outcome, reason) for the
     *                       {@code reconcile.knowledge.outcome} metric; reason is "none" for OK/EMPTY
     */
    public EkaKnowledgeClient(HttpClient http, ObjectMapper json, KnowledgeProperties props, ServiceTokenProvider tokens,
                              InjectionScanner scanner, BiConsumer<String, String> outcomeCounter) {
        this.http = http;
        this.json = json;
        this.props = props;
        this.tokens = tokens;
        this.scanner = scanner;
        this.outcomeCounter = outcomeCounter;
        this.breaker = CircuitBreaker.of("eka-knowledge", CircuitBreakerConfig.custom()
                .failureRateThreshold(props.breakerFailureRate())
                .slidingWindowSize(props.breakerWindow())
                .minimumNumberOfCalls(props.breakerWindow())
                .waitDurationInOpenState(props.breakerOpenFor())
                .recordException(e -> e instanceof Unavailable)
                .build());
    }

    CircuitBreaker breaker() {
        return breaker;
    }

    @Override
    public KnowledgeResult search(String tenantId, String query) {
        KnowledgeResult result = doSearch(tenantId, query);
        outcomeCounter.accept(result.outcome().name(), result.reason() == null ? "none" : result.reason());
        log.info("knowledge lookup tenant={} outcome={} reason={} hits={}", tenantId, result.outcome(),
                result.reason(), result.hits().size());
        return result;
    }

    private KnowledgeResult doSearch(String tenantId, String query) {
        String masked = PiiMasker.mask(query == null ? "" : query).text().strip();
        if (masked.isEmpty()) {
            return KnowledgeResult.rejected("QUERY_EMPTY");
        }
        if (masked.length() > EKA_MAX_QUERY_CHARS) {
            masked = masked.substring(0, EKA_MAX_QUERY_CHARS);
        }
        String token;
        try {
            token = tokens.token(tenantId);
        } catch (ServiceTokenProvider.ServiceTokenException e) {
            return KnowledgeResult.unavailable(e.code()); // configuration fault: nothing sent, breaker untouched
        }
        String finalQuery = masked;
        try {
            JsonNode body = breaker.executeCallable(() -> call(token, finalQuery));
            return screen(body);
        } catch (CallNotPermittedException open) {
            return KnowledgeResult.unavailable("CIRCUIT_OPEN");
        } catch (Unavailable e) {
            return KnowledgeResult.unavailable(e.getMessage());
        } catch (Rejected.AuthFault e) {
            return KnowledgeResult.unavailable("AUTH");
        } catch (Rejected e) {
            return KnowledgeResult.rejected(e.getMessage());
        } catch (Exception e) {
            return KnowledgeResult.unavailable("CLIENT_ERROR");
        }
    }

    private JsonNode call(String token, String query) {
        ObjectNode request = json.createObjectNode().put("query", query).put("top_k", props.topK());
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(URI.create(stripSlash(props.baseUrl()) + SEARCH_PATH))
                    .timeout(props.requestTimeout())
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                    .build();
        } catch (IOException e) {
            throw new Unavailable("CLIENT_ERROR");
        }
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new Unavailable("TIMEOUT");
        } catch (IOException e) {
            throw new Unavailable("NETWORK");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("INTERRUPTED");
        }
        int status = response.statusCode();
        if (status == 200) {
            try {
                JsonNode body = json.readTree(response.body());
                if (body == null || !body.path("results").isArray()) {
                    throw new Unavailable("BAD_RESPONSE");
                }
                return body;
            } catch (IOException e) {
                throw new Unavailable("BAD_RESPONSE");
            }
        }
        if (status == 401 || status == 403) {
            // A configuration fault (audience, client, issuer). Not counted by the breaker: EKA itself is up.
            throw new Rejected.AuthFault();
        }
        if (status == 400 || status == 422) {
            throw new Rejected("QUERY_INVALID");
        }
        if (status == 429) {
            throw new Unavailable("RATE_LIMITED");
        }
        throw new Unavailable("HTTP_" + status);
    }

    /** Screens EKA's hits. Every dropped hit is counted by rule name only; its text is never logged. */
    private KnowledgeResult screen(JsonNode body) {
        JsonNode results = body.path("results");
        if (results.isEmpty()) {
            return KnowledgeResult.empty();
        }
        List<KnowledgeHit> accepted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int budget = props.maxTotalChars();
        int dropped = 0;
        for (JsonNode r : results) {
            String chunkId = r.path("chunk_id").asText("");
            String text = r.path("text").asText("");
            if (chunkId.isBlank() || text.isBlank() || !seen.add(chunkId)) {
                dropped++;
                continue;
            }
            var rule = scanner.scan(text);
            if (rule.isPresent()) {
                log.warn("knowledge hit dropped by screening rule={}", rule.get());
                dropped++;
                continue;
            }
            String snippet = PiiMasker.mask(text).text().strip();
            if (snippet.length() > props.maxSnippetChars()) {
                snippet = snippet.substring(0, props.maxSnippetChars());
            }
            if (snippet.length() > budget) {
                break; // best-first: lower-ranked hits beyond the prompt budget are not sent
            }
            budget -= snippet.length();
            accepted.add(new KnowledgeHit(KnowledgeHit.REF_PREFIX + chunkId, r.path("document_id").asText(""),
                    r.path("filename").asText(""), snippet, cosine(r)));
        }
        if (accepted.isEmpty()) {
            return dropped > 0 ? KnowledgeResult.rejected("ALL_HITS_SCREENED") : KnowledgeResult.empty();
        }
        return KnowledgeResult.ok(accepted);
    }

    /** EKA's {@code scores.cosine} (required by the contract); null if missing or not a number. */
    private static Double cosine(JsonNode result) {
        JsonNode c = result.path("scores").path("cosine");
        return c.isNumber() ? c.asDouble() : null;
    }

    private static String stripSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Counted as a failure by the breaker. The message is the reason code. */
    static class Unavailable extends RuntimeException {
        Unavailable(String reason) {
            super(reason, null, false, false);
        }
    }

    /** Not counted by the breaker: EKA answered, it just refused this request. */
    static class Rejected extends RuntimeException {
        Rejected(String reason) {
            super(reason, null, false, false);
        }

        /** 401/403: reported as UNAVAILABLE {@code AUTH} (TDD 4.3), but EKA is healthy so the breaker ignores it. */
        static final class AuthFault extends Rejected {
            AuthFault() {
                super("AUTH");
            }
        }
    }
}
