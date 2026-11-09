package com.reconcileai.infrastructure.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.Collection;

/**
 * Stateless JWT resource-server security.
 *
 * <h2>Request flow</h2>
 * <pre>
 *  HTTP request
 *    -> BearerTokenAuthenticationFilter        (extracts "Authorization: Bearer ...")
 *    -> JwtDecoder (this class)                (RS256 signature + exp/nbf + tenant_id present)
 *         invalid/missing token  ------------>  401 + WWW-Authenticate
 *    -> JwtAuthenticationConverter             (roles claim -> ROLE_* authorities, unknown dropped)
 *    -> @PreAuthorize on controller method     (role check)
 *         wrong role  ----------------------->  403
 *    -> service -> repository(tenantId from TenantContext)
 *         row belongs to another tenant ----->  404 (never 403: 403 would confirm the id exists)
 * </pre>
 *
 * <h2>Key material</h2>
 * Local dev/test use a throwaway RSA keypair (public key on the classpath, private key under
 * src/test and scripts/). There is no Authorization Server in this project.
 * In a real deployment set {@code JWT_PUBLIC_KEY} to the IdP key or switch to
 * {@code jwk-set-uri}; nothing else in the app changes.
 */
@Configuration
@EnableMethodSecurity // REQUIRED: without it every @PreAuthorize annotation is silently ignored.
public class SecurityConfig {

    /** Same switch as springdoc: when off, the paths below do not exist, so there is nothing to open. */
    @Value("${springdoc.api-docs.enabled:false}")
    private boolean swaggerEnabled;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Stateless bearer-token API: no cookies, no browser session, so CSRF does not apply.
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                if (swaggerEnabled) {
                    // API docs: spec and UI are public so the page can load; calls made from it still need a JWT.
                    auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
                }
                auth
                // Liveness only. Every other actuator endpoint stays unexposed (see application.yml).
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Prometheus scrape: aggregate counters only (no tenant ids, prompts or amounts in any tag).
                // In a real deployment restrict it by network policy or move it to management.server.port.
                .requestMatchers("/actuator/prometheus").permitAll()
                // Demo persona login for the local UI. The controller exists ONLY under the demo-login profile;
                // without it these paths simply do not exist.
                .requestMatchers("/dev/personas", "/dev/token").permitAll()
                // Public sign-in metadata for the UI (IdP issuer + public client id; no secrets).
                .requestMatchers("/api/v1/auth/config").permitAll()
                // SSE: SseEmitter completes via an ASYNC re-dispatch of a request that was ALREADY
                // authenticated and authorised on its REQUEST dispatch. The bearer filter does not re-run on async
                // dispatch, so without this the stream ends with AccessDenied on an already-committed response.
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                // Default-deny: anything not explicitly opened requires a valid JWT.
                .anyRequest().authenticated();
            })
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Custom decoder instead of Boot's auto-configured one, for ONE reason: to add a validator
     * that requires the {@code tenant_id} claim. Boot's property-based decoder only checks
     * signature and timestamps; a validly signed token with no tenant would otherwise reach a
     * service and fail with a 500.
     *
     * <p>Defining this bean makes Boot back off its own decoder, so we must re-apply the default
     * timestamp validators ({@link JwtValidators#createDefault()}) ourselves.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.public-key-location}") Resource publicKeyPem,
            @Value("${reconcile.security.issuer-uri:}") String issuerUri,
            @Value("${reconcile.security.audience:}") String audience)
            throws IOException {

        OAuth2TokenValidator<Jwt> tenantRequired = new JwtClaimValidator<String>(
            TenantContext.TENANT_CLAIM, tenant -> tenant != null && !tenant.isBlank());

        if (!issuerUri.isBlank()) {
            return identityProviderDecoder(issuerUri, audience, tenantRequired);
        }

        RSAPublicKey publicKey;
        try (InputStream in = publicKeyPem.getInputStream()) {
            publicKey = RsaKeyConverters.x509().convert(in); // parses "-----BEGIN PUBLIC KEY-----" PEM
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
            .signatureAlgorithm(SignatureAlgorithm.RS256) // pin alg: rejects "none"/HS256 confusion
            .build();

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(), // exp / nbf with 60s clock skew
            tenantRequired));
        return decoder;
    }

    /**
     * Real identity provider mode ({@code reconcile.security.issuer-uri}, e.g. Keycloak
     * {@code http://localhost:8180/realms/reconcile} or Entra {@code https://login.microsoftonline.com/<tid>/v2.0}).
     *
     * <ul>
     *   <li>Signing keys come from the IdP's JWKS (discovered from the issuer) and rotate without a redeploy.</li>
     *   <li>{@code iss} must equal the configured issuer: a token from another realm or tenant is rejected.</li>
     *   <li>{@code aud} must contain the API's audience, so a token minted for a different client is rejected.</li>
     *   <li>The same {@code tenant_id} and {@code roles} claims are required (IdP protocol mappers produce them), so
     *       nothing downstream changes between demo keys and a real IdP.</li>
     *   <li>{@link SupplierJwtDecoder}: discovery happens on the first token, not at startup, so the API still boots
     *       (and serves health) while the IdP is briefly unavailable.</li>
     * </ul>
     */
    private static JwtDecoder identityProviderDecoder(String issuerUri, String audience,
                                                      OAuth2TokenValidator<Jwt> tenantRequired) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
            List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
            validators.add(JwtValidators.createDefaultWithIssuer(issuerUri));
            validators.add(tenantRequired);
            if (!audience.isBlank()) {
                validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    aud -> aud != null && aud.contains(audience)));
            }
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        });
    }

    /** Maps the JWT {@code roles} claim to Spring authorities using the closed {@link Role} set. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesConverter());
        // Principal name = sub. (Default already, stated explicitly so nobody "fixes" it to email.)
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    private Converter<Jwt, Collection<GrantedAuthority>> rolesConverter() {
        return jwt -> TenantContext.rolesOf(jwt).stream()
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.authority()))
            .toList();
    }
}
