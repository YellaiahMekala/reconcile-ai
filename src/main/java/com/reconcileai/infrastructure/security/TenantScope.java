package com.reconcileai.infrastructure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.function.Supplier;

/**
 * Which tenant the database session is bound to (Postgres Row-Level Security, see V6 migration).
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>An explicit scope opened with {@link #run}/{@link #call} on this thread (async jobs, Kafka listener,
 *       schedulers). Explicit wins, so a background thread can never inherit a stale request identity.</li>
 *   <li>The validated JWT of the current request ({@code tenant_id} claim).</li>
 *   <li>Nothing: the connection is bound to the empty tenant and every RLS policy returns no rows (fail closed).</li>
 * </ol>
 *
 * <h2>System scope</h2>
 * {@link #SYSTEM} is for genuinely cross-tenant platform work (outbox relay, recovery sweeper, daily digest,
 * audit anchoring). Every use is a named, reviewed call site; there is no ambient system access.
 */
public final class TenantScope {

    public static final String SYSTEM = "__system__";

    private static final ThreadLocal<String> SCOPE = new ThreadLocal<>();

    private TenantScope() {}

    public static void run(String tenantId, Runnable body) {
        call(tenantId, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T call(String tenantId, Supplier<T> body) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant scope needs a tenant id");
        }
        String previous = SCOPE.get();
        SCOPE.set(tenantId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(previous);
            }
        }
    }

    public static void runAsSystem(Runnable body) {
        run(SYSTEM, body);
    }

    public static <T> T callAsSystem(Supplier<T> body) {
        return call(SYSTEM, body);
    }

    /** @return the tenant for the next connection checkout, or "" when none (RLS then denies everything). */
    public static String current() {
        String explicit = SCOPE.get();
        if (explicit != null) {
            return explicit;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            Object tenant = jwt.getToken().getClaims().get(TenantContext.TENANT_CLAIM);
            if (tenant instanceof String s && !s.isBlank() && !SYSTEM.equals(s)) {
                return s; // a token can never claim the system scope
            }
        }
        return "";
    }
}
