package com.reconcileai.infrastructure.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.infrastructure.knowledge.KnowledgeProperties.TenantClient;
import com.reconcileai.infrastructure.knowledge.ServiceTokenProvider.ServiceTokenException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Per-tenant EKA service tokens: exact tenant match, typed failures, nothing sent for a wrong identity. */
class TenantServiceTokensTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "super-secret-value";

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String jwt(String tenant) {
        return b64("{}") + "." + b64("{\"tenant_id\":\"" + tenant + "\"}") + ".sig";
    }

    private final AtomicInteger factoryCalls = new AtomicInteger();
    private final Map<String, String> env = new HashMap<>(Map.of("EKA_SECRET_A", SECRET));
    private final Function<String, String> secrets = env::get;

    private TenantServiceTokens tokens(Supplier<String> source) {
        return new TenantServiceTokens(JSON, Map.of("tenant-a", new TenantClient("client-a", "EKA_SECRET_A")), secrets,
            client -> {
                factoryCalls.incrementAndGet();
                return source;
            });
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ServiceTokenException.class,
            e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void configuredTenantGetsItsToken() {
        String token = jwt("tenant-a");
        assertThat(tokens(() -> token).token("tenant-a")).isEqualTo(token);
    }

    @Test
    void unknownTenantIsRefusedWithoutBuildingASource() {
        TenantServiceTokens t = tokens(() -> jwt("tenant-a"));
        assertCode(() -> t.token("tenant-z"), "TENANT_CREDENTIALS_MISSING");
        assertThat(factoryCalls).hasValue(0);
    }

    @Test
    void nullTenantIsRefusedWithoutBuildingASource() {
        TenantServiceTokens t = tokens(() -> jwt("tenant-a"));
        assertCode(() -> t.token(null), "TENANT_CREDENTIALS_MISSING");
        assertThat(factoryCalls).hasValue(0);
    }

    @Test
    void tokenForAnotherTenantIsAMismatch() {
        assertCode(() -> tokens(() -> jwt("tenant-b")).token("tenant-a"), "TOKEN_TENANT_MISMATCH");
    }

    @Test
    void opaqueTokenCannotBeVerified() {
        assertCode(() -> tokens(() -> "opaque-token-without-dots").token("tenant-a"), "TOKEN_TENANT_UNVERIFIED");
    }

    @Test
    void tokenWithoutTenantClaimCannotBeVerified() {
        String noClaim = b64("{}") + "." + b64("{\"azp\":\"x\"}") + ".sig";
        assertCode(() -> tokens(() -> noClaim).token("tenant-a"), "TOKEN_TENANT_UNVERIFIED");
    }

    @Test
    void failingSourceIsTokenUnavailable() {
        assertCode(() -> tokens(() -> {
            throw new IllegalStateException("endpoint down, secret=" + SECRET);
        }).token("tenant-a"), "TOKEN_UNAVAILABLE");
    }

    @Test
    void nearMissTenantKeyDoesNotMatch() {
        TenantServiceTokens t = tokens(() -> jwt("tenant-a"));
        assertCode(() -> t.token("TENANT_A"), "TENANT_CREDENTIALS_MISSING");
        assertCode(() -> t.token("Tenant-A"), "TENANT_CREDENTIALS_MISSING");
        assertCode(() -> t.token(" tenant-a"), "TENANT_CREDENTIALS_MISSING");
        assertThat(factoryCalls).hasValue(0);
    }

    @Test
    void missingSecretEnvFailsAtConstructionWithoutLeakingSecrets() {
        assertThatThrownBy(() -> new TenantServiceTokens(JSON,
            Map.of("tenant-a", new TenantClient("client-a", "EKA_SECRET_MISSING")), secrets, c -> () -> "x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tenant-a")
            .hasMessageContaining("EKA_SECRET_MISSING")
            .hasMessageNotContaining(SECRET);
    }

    @Test
    void blankSecretValueFailsAtConstruction() {
        env.put("EKA_SECRET_BLANK", "  ");
        assertThatThrownBy(() -> new TenantServiceTokens(JSON,
            Map.of("tenant-a", new TenantClient("client-a", "EKA_SECRET_BLANK")), secrets, c -> () -> "x"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankClientIdFailsAtConstructionWithoutLeakingSecrets() {
        assertThatThrownBy(() -> new TenantServiceTokens(JSON,
            Map.of("tenant-a", new TenantClient(" ", "EKA_SECRET_A")), secrets, c -> () -> "x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("client-id")
            .hasMessageNotContaining(SECRET);
    }

    @Test
    void sourceIsCreatedOncePerTenant() {
        TenantServiceTokens t = tokens(() -> jwt("tenant-a"));
        t.token("tenant-a");
        t.token("tenant-a");
        t.token("tenant-a");
        assertThat(factoryCalls).hasValue(1);
    }
}
