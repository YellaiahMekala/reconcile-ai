// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * An ephemeral ES256 (P-256) key pair, generated once per test JVM, for the RERO run-token tests. Nothing is written
 * to disk; the PEMs are handed to the application through dynamic properties. {@link #sign} builds tokens with any
 * claim tampered, for the negative cases.
 */
public final class RunTokenTestKeys {

    public static final String KID = "test-run-key";
    private static final KeyPair PAIR = generate();
    private static final KeyPair FOREIGN = generate();

    private RunTokenTestKeys() {}

    public static String privatePem() {
        return pem("PRIVATE KEY", PAIR.getPrivate().getEncoded());
    }

    public static String publicPem() {
        return pem("PUBLIC KEY", PAIR.getPublic().getEncoded());
    }

    /** Valid claims for this tenant/job, then {@code tamper} applied; signed with the app's key unless {@code foreign}. */
    public static String sign(String tenant, UUID jobId, UnaryOperator<JWTClaimsSet.Builder> tamper, boolean foreign) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer("reconcile-ai").audience("reconcile-internal").subject("rero")
            .claim("tenant_id", tenant).claim("job_id", jobId.toString()).claim("scope", "tools:read")
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(90)));
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KID).build(),
                tamper.apply(claims).build());
            jwt.sign(new ECDSASigner((ECPrivateKey) (foreign ? FOREIGN : PAIR).getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(String tenant, UUID jobId) {
        return sign(tenant, jobId, b -> b, false);
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
            + "\n-----END " + type + "-----\n";
    }
}
