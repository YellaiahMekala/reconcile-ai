package com.reconcileai.infrastructure.security;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed set of application roles (NFR-01).
 *
 * <p>WHY AN ENUM: the JWT "roles" claim is attacker-influenced input (anyone holding a signing
 * key, or a misconfigured IdP, can put arbitrary strings there). Mapping claim values through
 * this enum means an unknown value such as "ADMIN" or "ROLE_SUPERUSER" is DROPPED rather than
 * turned into a Spring authority. Only these four can ever reach an {@code @PreAuthorize} check.
 *
 * <ul>
 *   <li>{@link #RECON_ANALYST} - creates breaks, uploads evidence, starts investigations.</li>
 *   <li>{@link #APPROVER_L1}   - approves proposals below the L2 materiality threshold.</li>
 *   <li>{@link #APPROVER_L2}   - approves proposals with variance >= 25,000 USD.</li>
 *   <li>{@link #SRE_ADMIN}     - read-only ops access (health, audit review).</li>
 * </ul>
 */
public enum Role {
    RECON_ANALYST,
    APPROVER_L1,
    APPROVER_L2,
    SRE_ADMIN;

    /** Spring Security's hasRole('X') checks for the authority "ROLE_X". */
    public String authority() {
        return "ROLE_" + name();
    }

    /**
     * Lenient parse of a claim value: accepts "RECON_ANALYST" or "ROLE_RECON_ANALYST".
     * Returns empty (never throws) for unknown values so a bad claim degrades to "no role"
     * (=> 403) instead of a 500.
     */
    public static Optional<Role> fromClaim(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.startsWith("ROLE_") ? raw.substring(5) : raw;
        return Arrays.stream(values()).filter(r -> r.name().equals(normalized)).findFirst();
    }
}
