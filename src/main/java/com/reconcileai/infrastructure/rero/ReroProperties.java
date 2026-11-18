package com.reconcileai.infrastructure.rero;

import com.reconcileai.infrastructure.knowledge.KnowledgeProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code reconcile.rero.*}: the RERO investigation mode (platform Q3, Contract C). Off by default
 * ({@code RERO_ENABLED=false}); then the mode is refused with 409 and the internal API accepts no token.
 *
 * @param enabled           master switch for the RERO mode
 * @param baseUrl           RERO API base URL ({@code POST /v1/recommendations}, {@code GET /v1/recommendations/{id}})
 * @param tokenUri          platform realm token endpoint (client credentials grant)
 * @param tokenScope        scope(s) that make the realm add both audiences {@code rero-api} and {@code ai-gateway}
 * @param requiredAudiences audiences the service token must carry before it is sent (TDD 2.2 two-audience exception)
 * @param modelViaGateway   operator's declaration that RERO runs its model calls through ai-gateway (GatewayModel).
 *                          While reconcile-ai's own gateway route is on, RERO counts as governed only when this is
 *                          true; otherwise it is refused with {@code MODE_NOT_GOVERNED}, like the agent modes
 * @param connectTimeout    HTTP connect timeout to RERO and the token endpoint
 * @param requestTimeout    per HTTP request (one POST or one poll)
 * @param pollInterval      delay between two status polls
 * @param deadlineSeconds   RERO run deadline (contract: 5 to 120)
 * @param deadlineGrace     extra time reconcile-ai waits past the deadline before failing the job itself
 * @param maxToolCalls      internal tool budget per run (contract: 1 to 20)
 * @param maxEvidenceChars  characters kept from one evidence chunk on the internal API
 * @param runToken          ES256 run-token key pair (PEM, from the environment) and key id
 * @param tenants           one platform-realm client per reconcile-ai tenant; exact tenant match, no shared client
 */
@ConfigurationProperties("reconcile.rero")
public record ReroProperties(boolean enabled, String baseUrl, String tokenUri, String tokenScope,
                             List<String> requiredAudiences, boolean modelViaGateway, Duration connectTimeout,
                             Duration requestTimeout, Duration pollInterval, int deadlineSeconds, Duration deadlineGrace,
                             int maxToolCalls, int maxEvidenceChars, RunToken runToken,
                             Map<String, KnowledgeProperties.TenantClient> tenants) {

    /** Contract C: exactly two model calls per run (draft and self-check). Not configurable. */
    public static final int MAX_MODEL_CALLS = 2;

    /**
     * @param kid           key id put in the JWS header
     * @param privateKeyPem PKCS#8 EC P-256 private key ({@code -----BEGIN PRIVATE KEY-----}); from an environment
     *                      variable or secret manager, never committed and never logged
     * @param publicKeyPem  matching X.509 public key; used only by the internal security chain
     */
    public record RunToken(String kid, String privateKeyPem, String publicKeyPem) {
        public RunToken {
            kid = kid == null || kid.isBlank() ? "rero-run-1" : kid;
        }

        public boolean configured() {
            return privateKeyPem != null && !privateKeyPem.isBlank() && publicKeyPem != null && !publicKeyPem.isBlank();
        }

        @Override
        public String toString() { // the record default would print the private key
            return "RunToken[kid=" + kid + ", configured=" + configured() + "]";
        }
    }

    public ReroProperties {
        requiredAudiences = requiredAudiences == null || requiredAudiences.isEmpty()
            ? List.of("rero-api", "ai-gateway") : List.copyOf(requiredAudiences);
        tokenScope = tokenScope == null || tokenScope.isBlank() ? "aud-rero-api aud-ai-gateway" : tokenScope;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        pollInterval = pollInterval == null ? Duration.ofMillis(500) : pollInterval;
        deadlineGrace = deadlineGrace == null ? Duration.ofSeconds(5) : deadlineGrace;
        deadlineSeconds = deadlineSeconds == 0 ? 60 : deadlineSeconds;
        maxToolCalls = maxToolCalls == 0 ? 8 : maxToolCalls;
        maxEvidenceChars = maxEvidenceChars <= 0 ? 1500 : maxEvidenceChars;
        runToken = runToken == null ? new RunToken(null, null, null) : runToken;
        tenants = tenants == null ? Map.of() : Map.copyOf(tenants);
        if (deadlineSeconds < 5 || deadlineSeconds > 120) {
            throw new IllegalArgumentException("reconcile.rero.deadline-seconds must be 5 to 120");
        }
        if (maxToolCalls < 1 || maxToolCalls > 20) {
            throw new IllegalArgumentException("reconcile.rero.max-tool-calls must be 1 to 20");
        }
    }

    /** Run-token lifetime: the run deadline plus 30 seconds (Contract C section 3). */
    public Duration runTokenTtl() {
        return Duration.ofSeconds(deadlineSeconds + 30L);
    }
}
