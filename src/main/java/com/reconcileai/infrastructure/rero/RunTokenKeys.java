package com.reconcileai.infrastructure.rero;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;

import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * The ES256 run-token key pair, parsed once from PEM configuration ({@code RERO_RUN_TOKEN_PRIVATE_KEY_PEM} /
 * {@code RERO_RUN_TOKEN_PUBLIC_KEY_PEM}). Only P-256 keys are accepted. Parse errors never echo key material.
 * <p>
 * {@link #none()} (no keys configured) is the state of a default build: no token can be minted and the internal
 * security chain rejects every request.
 */
public final class RunTokenKeys {

    private final String kid;
    private final ECPrivateKey privateKey;
    private final ECPublicKey publicKey;

    private RunTokenKeys(String kid, ECPrivateKey privateKey, ECPublicKey publicKey) {
        this.kid = kid;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static RunTokenKeys none() {
        return new RunTokenKeys(null, null, null);
    }

    public static RunTokenKeys from(ReroProperties.RunToken config) {
        if (config == null || !config.configured()) {
            return none();
        }
        try {
            KeyFactory ec = KeyFactory.getInstance("EC");
            ECPrivateKey priv = (ECPrivateKey) ec.generatePrivate(new PKCS8EncodedKeySpec(der(config.privateKeyPem())));
            ECPublicKey pub = (ECPublicKey) ec.generatePublic(new X509EncodedKeySpec(der(config.publicKeyPem())));
            if (pub.getParams().getCurve().getField().getFieldSize() != 256
                    || priv.getParams().getCurve().getField().getFieldSize() != 256) {
                throw new IllegalStateException("reconcile.rero.run-token keys must be EC P-256 (ES256)");
            }
            return new RunTokenKeys(config.kid(), priv, pub);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // Deliberately no cause message: it could quote part of the key.
            throw new IllegalStateException("reconcile.rero.run-token PEM keys could not be parsed ("
                + e.getClass().getSimpleName() + ")");
        }
    }

    public boolean present() {
        return privateKey != null && publicKey != null;
    }

    public String kid() {
        return kid;
    }

    ECPrivateKey privateKey() {
        return privateKey;
    }

    /** The verification JWK (public part only), or empty without keys. */
    Optional<ECKey> publicJwk() {
        if (!present()) {
            return Optional.empty();
        }
        return Optional.of(new ECKey.Builder(Curve.P_256, publicKey).keyID(kid).build());
    }

    /** Signer key check: the private key must belong to the configured public key. */
    void requireMatchingPair() throws JOSEException {
        if (!present()) {
            return;
        }
        com.nimbusds.jose.crypto.ECDSASigner signer = new com.nimbusds.jose.crypto.ECDSASigner(privateKey);
        com.nimbusds.jose.crypto.ECDSAVerifier verifier = new com.nimbusds.jose.crypto.ECDSAVerifier(publicKey);
        com.nimbusds.jose.JWSObject probe = new com.nimbusds.jose.JWSObject(
            new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.ES256),
            new com.nimbusds.jose.Payload("pair-check"));
        probe.sign(signer);
        if (!probe.verify(verifier)) {
            throw new IllegalStateException("reconcile.rero.run-token private and public keys do not match");
        }
    }

    private static byte[] der(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    @Override
    public String toString() {
        return "RunTokenKeys[kid=" + kid + ", present=" + present() + "]";
    }
}
