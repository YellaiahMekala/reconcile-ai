// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.pii;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls a Microsoft Presidio analyzer sidecar ({@code POST /analyze}) and returns the entities it found.
 * Text is sent to the sidecar inside the same trust boundary (cluster / compose network), never to the model provider.
 */
public class PresidioNerDetector implements NerDetector {

    private final HttpClient http;
    private final URI analyzeUri;
    private final List<String> entities;
    private final double scoreThreshold;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    public PresidioNerDetector(String baseUrl, List<String> entities, double scoreThreshold, Duration timeout) {
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.analyzeUri = URI.create(baseUrl.replaceAll("/+$", "") + "/analyze");
        this.entities = List.copyOf(entities);
        this.scoreThreshold = scoreThreshold;
        this.timeout = timeout;
    }

    @Override
    public List<Span> detect(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            var body = json.createObjectNode();
            body.put("text", text);
            body.put("language", "en");
            body.put("score_threshold", scoreThreshold);
            var arr = body.putArray("entities");
            entities.forEach(arr::add);
            HttpRequest req = HttpRequest.newBuilder(analyzeUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new NerUnavailableException("Presidio answered HTTP " + res.statusCode(), null);
            }
            List<Span> spans = new ArrayList<>();
            for (JsonNode n : json.readTree(res.body())) {
                spans.add(new Span(n.path("entity_type").asText(), n.path("start").asInt(), n.path("end").asInt(),
                    n.path("score").asDouble()));
            }
            return spans;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NerUnavailableException("Presidio call interrupted", e);
        } catch (IOException e) {
            throw new NerUnavailableException("Presidio unreachable: " + e.getClass().getSimpleName(), e);
        }
    }
}
