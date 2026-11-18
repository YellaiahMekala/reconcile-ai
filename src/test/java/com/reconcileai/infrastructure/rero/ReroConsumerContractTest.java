package com.reconcileai.infrastructure.rero;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.rero.ReroCallException;
import com.reconcileai.application.rero.ReroClientPort;
import com.reconcileai.application.rero.ReroClientPort.ReroRun;
import com.reconcileai.support.ReroContracts;
import com.reconcileai.support.RunTokenTestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Platform Q3.5 consumer side of Contract C, against the schemas copied from {@code platform/contracts/rero/}:
 * our start request validates against {@code start-request.v1.json}; every valid Run example parses; every invalid Run
 * example is refused as {@code RERO_INVALID_RESPONSE} (never a proposal).
 */
class ReroConsumerContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"error", "internal-break", "internal-evidence", "internal-knowledge", "internal-similar",
        "run", "start-request"})
    void theCopiedSchemasAcceptTheirValidExamplesAndRejectTheInvalidOnes(String name) throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(getClass().getResource("/contracts/rero/examples").toURI());
        try (var files = java.nio.file.Files.list(dir)) {
            var examples = files.map(p -> p.getFileName().toString()).filter(f -> f.startsWith(name + ".")).toList();
            assertThat(examples).isNotEmpty();
            for (String f : examples) {
                var errors = ReroContracts.validate(name + ".v1", ReroContracts.resource("examples/" + f));
                if (f.contains(".valid")) {
                    assertThat(errors).as(f).isEmpty();
                } else {
                    assertThat(errors).as(f).isNotEmpty();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"run.valid-running.json", "run.valid-succeeded.json", "run.valid-succeeded-challenge.json",
        "run.valid-failed.json"})
    void validRunBodiesParse(String example) {
        ReroRun run = client(RestClient.builder()).parse(ReroContracts.resource("examples/" + example));
        assertThat(run.runId()).isNotNull();
        if (run.status() == ReroClientPort.Status.SUCCEEDED) {
            assertThat(run.result().citedChunkIds()).isNotEmpty();
            assertThat(run.result().selfCheck()).isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"run.invalid-failed-without-code.json", "run.invalid-succeeded-null-result.json",
        "run.invalid-unknown-failure-code.json"})
    void invalidRunBodiesAreRefused(String example) {
        HttpReroClient client = client(RestClient.builder());
        String body = ReroContracts.resource("examples/" + example);
        assertThatThrownBy(() -> client.parse(body)).isInstanceOfSatisfying(ReroCallException.class,
            e -> assertThat(e.code()).isEqualTo(ReroCallException.INVALID_RESPONSE));
    }

    @Test
    void ourStartRequestMatchesTheSchemaAndCarriesTheIdempotencyKey() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://rero.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID jobId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String[] sent = new String[1];
        server.expect(requestTo("http://rero.test/v1/recommendations"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Idempotency-Key", jobId.toString()))
            .andExpect(header("Authorization", "Bearer service-token-TENANT_X"))
            .andExpect(req -> sent[0] = ((MockClientHttpRequest) req).getBodyAsString(StandardCharsets.UTF_8))
            .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                .body(ReroContracts.resource("examples/run.valid-running.json")));

        ReroRun run = client(builder).start("TENANT_X", new ReroClientPort.StartRequest(jobId, "masked note",
            new ReroClientPort.Limits(8, 2, 60), RunTokenTestKeys.sign("TENANT_X", jobId)));

        server.verify();
        assertThat(ReroContracts.validate("start-request.v1", sent[0])).isEmpty();
        assertThat(JSON.readTree(sent[0]).has("tenantId")).isFalse();
        assertThat(run.status()).isEqualTo(ReroClientPort.Status.RUNNING);
    }

    @Test
    void errorStatusesAreTypedAndBodiesIgnored() {
        assertStatus(HttpStatus.CONFLICT, ReroCallException.IDEMPOTENCY_CONFLICT);
        assertStatus(HttpStatus.FORBIDDEN, ReroCallException.AUTH);
        assertStatus(HttpStatus.UNAUTHORIZED, ReroCallException.AUTH);
        assertStatus(HttpStatus.NOT_FOUND, ReroCallException.INVALID_RESPONSE);
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, ReroCallException.UNAVAILABLE);
        assertStatus(HttpStatus.TOO_MANY_REQUESTS, ReroCallException.UNAVAILABLE);
    }

    private void assertStatus(HttpStatus status, String code) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://rero.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID runId = UUID.randomUUID();
        server.expect(requestTo("http://rero.test/v1/recommendations/" + runId))
            .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                .body(ReroContracts.resource("examples/error.valid.json")));
        HttpReroClient client = client(builder);
        assertThatThrownBy(() -> client.get("TENANT_X", runId)).isInstanceOfSatisfying(ReroCallException.class,
            e -> assertThat(e.code()).isEqualTo(code));
    }

    private static HttpReroClient client(RestClient.Builder builder) {
        return new HttpReroClient(builder.build(), JSON, tenant -> "service-token-" + tenant);
    }
}
