// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.rero;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reconcileai.application.rero.ReroCallException;
import com.reconcileai.application.rero.ReroClientPort;
import com.reconcileai.infrastructure.knowledge.ServiceTokenProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Contract C client (platform Q3.5) on {@link RestClient} (servlet stack, no WebFlux).
 * <ul>
 *   <li>Auth: the job tenant's own service token (audiences {@code rero-api} + {@code ai-gateway}); no shared client.</li>
 *   <li>{@code POST /v1/recommendations}, {@code Idempotency-Key = jobId}; 202 and 200 both carry the run.</li>
 *   <li>Every non-2xx, timeout or unreadable body becomes a typed {@link ReroCallException}; bodies of errors are not
 *       read into logs. The run token in the request body is never logged.</li>
 * </ul>
 */
public class HttpReroClient implements ReroClientPort {

    static final String RECOMMENDATIONS = "/v1/recommendations";

    private final RestClient http;
    private final ObjectMapper json;
    private final ServiceTokenProvider tokens;

    public HttpReroClient(RestClient http, ObjectMapper json, ServiceTokenProvider tokens) {
        this.http = http;
        this.json = json;
        this.tokens = tokens;
    }

    @Override
    public ReroRun start(String tenantId, StartRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("jobId", request.jobId().toString());
        body.put("analystNote", request.analystNote() == null ? "" : request.analystNote());
        body.putObject("limits")
            .put("maxToolCalls", request.limits().maxToolCalls())
            .put("maxModelCalls", request.limits().maxModelCalls())
            .put("deadlineSeconds", request.limits().deadlineSeconds());
        body.put("runToken", request.runToken());
        String bearer = token(tenantId);
        return exchange(() -> http.post().uri(RECOMMENDATIONS)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .header("Idempotency-Key", request.jobId().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(write(body))
            .retrieve()
            .onStatus(s -> true, (req, res) -> { }) // statuses are mapped below, never thrown with a body
            .toEntity(String.class));
    }

    @Override
    public ReroRun get(String tenantId, UUID runId) {
        String bearer = token(tenantId);
        return exchange(() -> http.get().uri(RECOMMENDATIONS + "/{runId}", runId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .onStatus(s -> true, (req, res) -> { })
            .toEntity(String.class));
    }

    private String token(String tenantId) {
        try {
            return tokens.token(tenantId);
        } catch (RuntimeException e) {
            throw new ReroCallException(ReroCallException.TOKEN_UNAVAILABLE);
        }
    }

    private ReroRun exchange(java.util.function.Supplier<ResponseEntity<String>> call) {
        ResponseEntity<String> response;
        try {
            response = call.get();
        } catch (ResourceAccessException e) {
            throw new ReroCallException(ReroCallException.UNAVAILABLE);
        } catch (RestClientException e) {
            throw new ReroCallException(ReroCallException.UNAVAILABLE);
        }
        int status = response.getStatusCode().value();
        if (status == 200 || status == 202) {
            return parse(response.getBody());
        }
        if (status == 401 || status == 403) {
            throw new ReroCallException(ReroCallException.AUTH);
        }
        if (status == 409) {
            throw new ReroCallException(ReroCallException.IDEMPOTENCY_CONFLICT);
        }
        if (status == 400 || status == 404 || status == 422) {
            throw new ReroCallException(ReroCallException.INVALID_RESPONSE);
        }
        throw new ReroCallException(ReroCallException.UNAVAILABLE); // 429, 5xx and anything else
    }

    /** Strict parse of the Run body; anything outside the contract is INVALID_RESPONSE. Package-visible for tests. */
    ReroRun parse(String body) {
        try {
            JsonNode n = json.readTree(body == null ? "" : body);
            if (n == null || !n.isObject()) {
                throw new ReroCallException(ReroCallException.INVALID_RESPONSE);
            }
            Status status = Status.valueOf(n.path("status").asText());
            Stage stage = n.hasNonNull("stage") ? Stage.valueOf(n.path("stage").asText()) : null;
            List<String> invocations = new ArrayList<>();
            n.path("gatewayInvocationIds").forEach(i -> invocations.add(i.asText()));
            Result result = null;
            JsonNode r = n.path("result");
            if (r.isObject()) {
                List<String> cited = new ArrayList<>();
                r.path("citedChunkIds").forEach(c -> cited.add(c.asText()));
                result = new Result(text(r, "rootCauseCategory"), text(r, "explanation"), cited,
                    r.path("confidence").isNumber() ? new BigDecimal(r.path("confidence").asText()) : null,
                    r.hasNonNull("selfCheck") ? SelfCheck.valueOf(r.path("selfCheck").asText()) : null,
                    text(r, "selfCheckReason"));
            }
            String failureCode = text(n, "failureCode");
            boolean consistent = switch (status) {
                case RUNNING -> result == null && failureCode == null;
                case SUCCEEDED -> result != null && failureCode == null;
                case FAILED -> result == null && failureCode != null
                    && com.reconcileai.application.rero.ReroFailureCode.parse(failureCode)
                        != com.reconcileai.application.rero.ReroFailureCode.UNRECOGNISED;
            };
            if (!consistent) {
                throw new ReroCallException(ReroCallException.INVALID_RESPONSE);
            }
            return new ReroRun(UUID.fromString(n.path("runId").asText()), UUID.fromString(n.path("jobId").asText()),
                status, stage, failureCode, n.path("toolCallsUsed").asInt(0),
                n.path("modelCallsUsed").asInt(0), invocations, result);
        } catch (ReroCallException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new ReroCallException(ReroCallException.INVALID_RESPONSE);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String write(ObjectNode body) {
        try {
            return json.writeValueAsString(body);
        } catch (IOException e) {
            throw new ReroCallException(ReroCallException.INVALID_RESPONSE);
        }
    }
}
