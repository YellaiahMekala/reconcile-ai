package com.reconcileai.application.audit.anchor;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 signatures over the anchor statement. Asymmetric on purpose: an auditor verifies with the PUBLIC key and
 * never needs a secret; the private key stays with the service (secret manager / KMS in production).
 */
public final class AnchorSigner {

    private final String keyId;
    private final PublicKey publicKey;
    private final PrivateKey privateKey; // null on a verify-only instance

    public AnchorSigner(String keyId, String publicKeyBase64X509, String privateKeyBase64Pkcs8) {
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            this.keyId = keyId;
            this.publicKey = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64X509)));
            this.privateKey = privateKeyBase64Pkcs8 == null || privateKeyBase64Pkcs8.isBlank() ? null
                : kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64Pkcs8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Audit anchor signing keys are not valid Ed25519 (base64 X509 public, PKCS8 private)", e);
        }
    }

    /** For tests and one-off key generation: returns {publicKeyBase64, privateKeyBase64}. */
    public static String[] generate() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new String[]{Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String keyId() {
        return keyId;
    }

    /** The exact bytes that are signed; also rebuilt by the verifier, so any field change breaks the signature. */
    public static String statement(String tenantId, long sequence, String headHash, String anchoredAtIso) {
        return String.join("|", "reconcile-audit-anchor/v1", tenantId, Long.toString(sequence), headHash, anchoredAtIso);
    }

    public String sign(String statement) {
        if (privateKey == null) {
            throw new IllegalStateException("No private key configured: this instance can verify but not anchor");
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(privateKey);
            s.update(statement.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(String statement, String signatureBase64) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(publicKey);
            s.update(statement.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
