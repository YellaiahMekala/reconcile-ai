// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * LOCAL DEMO ONLY: issues short-lived tokens for fixed personas so the Angular UI works without an identity provider.
 *
 * <p>Exists only under the {@code demo-login} profile and refuses to start next to a {@code prod} profile. Tokens are
 * signed with the throwaway dev key ({@code scripts/jwt}) and validated by the normal resource-server path, so the UI
 * exercises exactly the same 401/403/404 rules as any other client. The caller picks a persona, never claims: tenant
 * and roles come from the fixed table below.
 */
@RestController
@Profile("demo-login")
public class DemoLoginController {

    public record Persona(String id, String subject, String tenantId, List<String> roles, String label) {}

    public record LoginRequest(String persona) {}

    public record LoginResponse(String token, Persona persona, Instant expiresAt) {}

    static final List<Persona> PERSONAS = List.of(
        new Persona("analyst", "analyst-1", "TENANT_DEMO", List.of("RECON_ANALYST"), "Recon analyst (maker)"),
        new Persona("approver-l1", "approver-l1", "TENANT_DEMO", List.of("APPROVER_L1"), "L1 approver"),
        new Persona("approver-l2", "approver-l2", "TENANT_DEMO", List.of("APPROVER_L1", "APPROVER_L2"), "L2 approver"),
        new Persona("sre", "sre-1", "TENANT_DEMO", List.of("SRE_ADMIN"), "SRE admin (audit verify)"),
        new Persona("other-tenant", "analyst-x", "TENANT_OTHER", List.of("RECON_ANALYST"), "Analyst in another tenant"));

    private final RSAPrivateKey key;

    public DemoLoginController(@Value("${reconcile.demo-login.private-key-location:file:scripts/jwt/dev-private-pkcs8.pem}")
                               Resource keyLocation, Environment env) throws IOException {
        if (Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("demo-login must never run with the prod profile");
        }
        this.key = load(keyLocation);
    }

    @GetMapping("/dev/personas")
    public List<Persona> personas() {
        return PERSONAS;
    }

    @PostMapping("/dev/token")
    public ResponseEntity<LoginResponse> token(@RequestBody LoginRequest request) {
        Persona p = PERSONAS.stream().filter(x -> x.id().equals(request.persona())).findFirst().orElse(null);
        if (p == null) {
            return ResponseEntity.badRequest().build();
        }
        Instant exp = Instant.now().plusSeconds(3600);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(p.subject())
            .claim("tenant_id", p.tenantId())
            .claim("roles", p.roles())
            .issueTime(new Date())
            .expirationTime(Date.from(exp))
            .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(key));
            return ResponseEntity.ok(new LoginResponse(jwt.serialize(), p, exp));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign demo token", e);
        }
    }

    private static RSAPrivateKey load(Resource r) throws IOException {
        String pem = new String(r.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
            .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("invalid demo-login private key", e);
        }
    }
}
