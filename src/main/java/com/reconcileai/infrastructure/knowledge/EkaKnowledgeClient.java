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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

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
 *   <tr><td>429</td><td>retried (see below); UNAVAILABLE {@code RATE_LIMITED} once the retries are used up</td></tr>
 *   <tr><td>5xx, other 4xx, bad body</td><td>UNAVAILABLE {@code HTTP_nnn} / {@code BAD_RESPONSE}</td></tr>
 *   <tr><td>timeout, network</td><td>UNAVAILABLE {@code TIMEOUT} / {@code NETWORK}</td></tr>
 *   <tr><td>breaker open</td><td>UNAVAILABLE {@code CIRCUIT_OPEN}, no request sent</td></tr>
 *   <tr><td>token failure</td><td>UNAVAILABLE with the token code ({@code TENANT_CREDENTIALS_MISSING}, ...)</td></tr>
 * </table>
 * <h2>Retry (HTTP 429 only)</h2>
 * A 429 is a short-lived EKA edge limit, so it is retried up to {@code rate-limit-retries} times (default 2): wait
 * {@code Retry-After} (500 ms if absent or not a number of seconds) plus random jitter, each wait capped at
 * {@code rate-limit-max-wait} (default 2 s), and never past the per-lookup {@code request-timeout} (a wait that would not
 * fit is skipped and the lookup ends {@code RATE_LIMITED}). Nothing else is retried: other 4xx are our fault, and 5xx
 * and timeouts are left to the circuit breaker. The breaker sees one result per lookup, and {@code RATE_LIMITED} is
 * recorded only after the retries are exhausted. Knowledge is advisory, so the investigation never waits longer than
 * the lookup timeout. Logs carry outcome codes, counts and screening rule names, never the query, a passage or a token.
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
    private final Consumer<String> retryCounter;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    /** Pauses the calling thread; replaceable so tests need not wait. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * @param outcomeCounter called once per lookup with (outcome, reason) for the
     *                       {@code reconcile.knowledge.outcome} metric; reason is "none" for OK/EMPTY
     */
    public EkaKnowledgeClient(HttpClient http, ObjectMapper json, KnowledgeProperties props, ServiceTokenProvider tokens,
                              InjectionScanner scanner, BiConsumer<String, String> outcomeCounter) {
        this(http, json, props, tokens, scanner, outcomeCounter, e -> { });
    }

    /**
     * @param retryCounter called with {@code "retry"} before each 429 retry and {@code "exhausted"} when a lookup
     *                     ends {@code RATE_LIMITED} after its retries (the {@code reconcile.knowledge.rate_limit_retry} metric)
     */
    public EkaKnowledgeClient(HttpClient http, ObjectMapper json, KnowledgeProperties props, ServiceTokenProvider tokens,
                              InjectionScanner scanner, BiConsumer<String, String> outcomeCounter,
                              Consumer<String> retryCounter) {
        this(http, json, props, tokens, scanner, outcomeCounter, retryCounter, Thread::sleep,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    EkaKnowledgeClient(HttpClient http, ObjectMapper json, KnowledgeProperties props, ServiceTokenProvider tokens,
                       InjectionScanner scanner, BiConsumer<String, String> outcomeCounter,
                       Consumer<String> retryCounter, Sleeper sleeper, DoubleSupplier random) {
        this.retryCounter = retryCounter;
        this.sleeper = sleeper;
        this.random = random;
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
        String requestBody;
        try {
            requestBody = json.writeValueAsString(json.createObjectNode().put("query", query).put("top_k", props.topK()));
        } catch (IOException e) {
            throw new Unavailable("CLIENT_ERROR");
        }
        long deadline = System.nanoTime() + props.requestTimeout().toNanos();
        int retries = 0;
        while (true) {
            Duration remaining = Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
            HttpResponse<String> response = send(token, requestBody, remaining);
            if (response.statusCode() != 429) {
                return handle(response);
            }
            long waitMs = retries < props.rateLimitRetries() ? retryWaitMillis(response) : -1;
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
            if (waitMs < 0 || waitMs >= remainingMs) {
                if (props.rateLimitRetries() > 0) {
                    retryCounter.accept("exhausted");
                }
                throw new Unavailable("RATE_LIMITED");
            }
            retries++;
            retryCounter.accept("retry");
            log.info("knowledge lookup rate limited, retry {} of {} in {} ms", retries, props.rateLimitRetries(), waitMs);
            try {
                sleeper.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Unavailable("INTERRUPTED");
            }
        }
    }

    /** {@code Retry-After} seconds (500 ms if absent or not a number) plus jitter, capped at {@code rate-limit-max-wait}. */
    long retryWaitMillis(HttpResponse<String> response) {
        long base = 500;
        String header = response.headers().firstValue("Retry-After").orElse(null);
        if (header != null) {
            try {
                double seconds = Double.parseDouble(header.strip());
                if (seconds >= 0 && seconds <= 3600) {
                    base = (long) (seconds * 1000);
                }
            } catch (NumberFormatException ignored) {
                // an HTTP-date or junk: use the default
            }
        }
        long jitter = (long) (random.getAsDouble() * props.rateLimitJitter().toMillis());
        return Math.min(base + jitter, props.rateLimitMaxWait().toMillis());
    }

    private HttpResponse<String> send(String token, String requestBody, Duration timeout) {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(stripSlash(props.baseUrl()) + SEARCH_PATH))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        try {
            return http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new Unavailable("TIMEOUT");
        } catch (IOException e) {
            throw new Unavailable("NETWORK");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("INTERRUPTED");
        }
    }

    private JsonNode handle(HttpResponse<String> response) {
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
