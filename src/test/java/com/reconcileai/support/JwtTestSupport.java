package com.reconcileai.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Mints RS256 JWTs for tests and for scripts/generate-local-jwt.ps1.
 *
 * <p>Signed with src/test/resources/jwt/dev-private-pkcs8.pem, which pairs with the public key the
 * app validates against. TEST-ONLY: this class lives under src/test, so it (and the private key)
 * are never packaged into the runnable jar.
 *
 * <p>Claims: {@code sub}, {@code tenant_id}, {@code roles[]}, {@code iat}, {@code exp}.
 *
 * <p>The negative-case helpers exist because a security test suite that only proves the happy path
 * proves nothing: we must show expired, unsigned-by-us, and tenant-less tokens are all rejected.
 */
public final class JwtTestSupport {

    private static final String DEV_PRIVATE_KEY = "/jwt/dev-private-pkcs8.pem";

    private JwtTestSupport() {}

    // ---------------------------------------------------------------- happy path

    /** Valid token for 1 hour, signed with the dev key. */
    public static String mint(String subject, String tenantId, List<String> roles) {
        return sign(baseClaims(subject, roles, Instant.now().plusSeconds(3600))
            .claim("tenant_id", tenantId).build(), loadDevPrivateKey());
    }

    /** Convenience: "Bearer " + token, ready for an Authorization header. */
    public static String bearer(String subject, String tenantId, List<String> roles) {
        return "Bearer " + mint(subject, tenantId, roles);
    }

    // ---------------------------------------------------------------- negative cases

    /** Correctly signed but expired well beyond the default 60s clock skew. */
    public static String mintExpired(String subject, String tenantId, List<String> roles) {
        return sign(baseClaims(subject, roles, Instant.now().minusSeconds(3600))
            .issueTime(Date.from(Instant.now().minusSeconds(7200)))
            .claim("tenant_id", tenantId).build(), loadDevPrivateKey());
    }

    /** Correctly signed and unexpired, but with NO tenant_id claim. Must be 401, not 500. */
    public static String mintWithoutTenant(String subject, List<String> roles) {
        return sign(baseClaims(subject, roles, Instant.now().plusSeconds(3600)).build(), loadDevPrivateKey());
    }

    /** Structurally valid token signed by a freshly generated key the app has never seen (forgery). */
    public static String mintWithForeignKey(String subject, String tenantId, List<String> roles) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            RSAPrivateKey foreign = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
            return sign(baseClaims(subject, roles, Instant.now().plusSeconds(3600))
                .claim("tenant_id", tenantId).build(), foreign);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- internals

    private static JWTClaimsSet.Builder baseClaims(String subject, List<String> roles, Instant expiry) {
        return new JWTClaimsSet.Builder()
            .subject(subject)
            .claim("roles", roles)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(expiry));
    }

    private static String sign(JWTClaimsSet claims, RSAPrivateKey key) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }

    private static RSAPrivateKey loadDevPrivateKey() {
        try (InputStream in = JwtTestSupport.class.getResourceAsStream(DEV_PRIVATE_KEY)) {
            if (in == null) {
                throw new IllegalStateException("Private key not found on classpath: " + DEV_PRIVATE_KEY);
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", ""); // strips CRLF too, so Windows checkouts work
            byte[] der = Base64.getDecoder().decode(pem);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load dev private key", e);
        }
    }

    /** CLI used by scripts/generate-local-jwt.ps1. Args: subject tenantId role[,role]. */
    public static void main(String[] args) {
        String subject = args.length > 0 ? args[0] : "dev-user";
        String tenantId = args.length > 1 ? args[1] : "TENANT_A";
        List<String> roles = args.length > 2 ? List.of(args[2].split(",")) : List.of("RECON_ANALYST");
        System.out.println(mint(subject, tenantId, roles));
    }
}
