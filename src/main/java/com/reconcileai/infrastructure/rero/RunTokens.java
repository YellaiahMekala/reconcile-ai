package com.reconcileai.infrastructure.rero;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.reconcileai.application.rero.RunTokenIssuer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Run capability tokens (TDD 2.3, Contract C section 3): minting for RERO and verification for the internal chain.
 * <pre>
 * header: alg=ES256, kid, typ=JWT
 * claims: iss=reconcile-ai, aud=reconcile-internal, sub=rero, tenant_id, job_id, scope="tools:read", jti, iat, exp
 * </pre>
 * The verifier pins ES256 and the configured key id, and checks issuer, audience, subject, scope, a short lifetime and
 * the presence of tenant and job. Job state (RUNNING, mode RERO, path job id) is checked per request by the internal
 * tool service: that is server-side invalidation, not revocation. Tokens are never logged or stored.
 */
public final class RunTokens implements RunTokenIssuer {

    public static final String ISSUER = "reconcile-ai";
    public static final String AUDIENCE = "reconcile-internal";
    public static final String SUBJECT = "rero";
    public static final String SCOPE = "tools:read";
    public static final String JOB_CLAIM = "job_id";
    public static final String TENANT_CLAIM = "tenant_id";
    /** Longest run-token lifetime the verifier accepts: max deadline 120 s + 30 s, plus clock skew. */
    static final Duration MAX_LIFETIME = Duration.ofSeconds(150);
    static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    private final RunTokenKeys keys;
    private final Clock clock;

    public RunTokens(RunTokenKeys keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    @Override
    public String mint(String tenantId, UUID jobId, Duration ttl) {
        if (!keys.present()) {
            throw new IllegalStateException("RUN_TOKEN_KEYS_MISSING");
        }
        if (tenantId == null || tenantId.isBlank() || jobId == null) {
            throw new IllegalArgumentException("run token needs a tenant and a job");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("run token ttl must be 1 to 150 seconds");
        }
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .subject(SUBJECT)
            .claim(TENANT_CLAIM, tenantId)
            .claim(JOB_CLAIM, jobId.toString())
            .claim("scope", SCOPE)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keys.kid())
                .type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new ECDSASigner(keys.privateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("RUN_TOKEN_SIGNING_FAILED");
        }
    }

    /**
     * The decoder used only by the internal security chain. Without keys it rejects everything. Not a Spring bean of
     * type {@link JwtDecoder}: the user chain must keep resolving its own decoder unambiguously.
     */
    public JwtDecoder decoder() {
        return keys.publicJwk().<JwtDecoder>map(jwk -> {
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
                new ImmutableJWKSet<>(new JWKSet(jwk))));
            processor.setJWTClaimsSetVerifier((claims, context) -> { }); // claims are checked by the validators below
            NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
            JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
            timestamps.setClock(clock);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                timestamps,
                lifetime(clock),
                new JwtIssuerValidator(ISSUER),
                claim("aud", jwt -> jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)),
                claim("sub", jwt -> SUBJECT.equals(jwt.getSubject())),
                claim("scope", jwt -> SCOPE.equals(jwt.getClaimAsString("scope"))),
                claim(TENANT_CLAIM, jwt -> notBlank(jwt.getClaimAsString(TENANT_CLAIM))),
                claim(JOB_CLAIM, jwt -> isUuid(jwt.getClaimAsString(JOB_CLAIM))),
                claim("jti", jwt -> notBlank(jwt.getId())))));
            return decoder;
        }).orElse(token -> {
            throw new BadJwtException("run tokens are not configured");
        });
    }

    /** exp and iat are required, and the token may not live longer than any legitimate run could need. */
    private static OAuth2TokenValidator<Jwt> lifetime(Clock clock) {
        return jwt -> {
            Instant iat = jwt.getIssuedAt();
            Instant exp = jwt.getExpiresAt();
            boolean ok = iat != null && exp != null && exp.isAfter(iat)
                && Duration.between(iat, exp).compareTo(MAX_LIFETIME) <= 0
                && !iat.isAfter(clock.instant().plus(CLOCK_SKEW));
            return ok ? OAuth2TokenValidatorResult.success() : failure("lifetime");
        };
    }

    private static OAuth2TokenValidator<Jwt> claim(String name, java.util.function.Predicate<Jwt> test) {
        return jwt -> test.test(jwt) ? OAuth2TokenValidatorResult.success() : failure(name);
    }

    private static OAuth2TokenValidatorResult failure(String what) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "run token " + what + " invalid", null));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isUuid(String s) {
        if (s == null) {
            return false;
        }
        try {
            return UUID.fromString(s).toString().equalsIgnoreCase(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
