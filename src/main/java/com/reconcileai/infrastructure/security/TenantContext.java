// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single source of caller identity for the whole application.
 *
 * <h2>Rule</h2>
 * Identity is read ONLY from the validated JWT sitting in {@link SecurityContextHolder}.
 * Never from a request parameter, a request body field, a client header such as
 * {@code X-Tenant-Id}, or an LLM tool-call argument. Because no API accepts a tenant,
 * "act as another tenant" is structurally impossible, not merely discouraged.
 *
 * <h2>Why not cast the principal to UserDetails?</h2>
 * Under {@code spring-boot-starter-oauth2-resource-server} the Authentication is a
 * {@link JwtAuthenticationToken} and its principal is a {@link Jwt}. Casting to
 * {@code UserDetails} (a common copy-paste from form-login examples) throws
 * {@code ClassCastException} at runtime. We read claims off the {@link Jwt} directly.
 *
 * <h2>Why can requireTenantId() assume the claim exists?</h2>
 * {@link SecurityConfig#jwtDecoder} installs a validator that rejects any token without a
 * non-blank {@code tenant_id} with 401 BEFORE the request reaches a controller. The check here
 * is a belt-and-braces guard for code paths that run outside a web request (e.g. tests).
 *
 * <h2>Threading note</h2>
 * SecurityContextHolder is thread-local. Async investigation jobs must capture a
 * {@link CurrentUser} on the request thread and pass it explicitly; they must not call this
 * class from a pool thread.
 */
@Component
public class TenantContext {

    public static final String TENANT_CLAIM = "tenant_id";
    public static final String ROLES_CLAIM = "roles";

    /** Full caller snapshot: subject + tenant + known roles. */
    public CurrentUser currentUser() {
        Jwt jwt = currentJwt();
        return new CurrentUser(jwt.getSubject(), tenantOf(jwt), rolesOf(jwt));
    }

    /** Shortcut used by every tenant-scoped repository call. */
    public String requireTenantId() {
        return tenantOf(currentJwt());
    }

    /** Subject (user id) of the current caller; used as the maker/checker actor. */
    public String requireSubject() {
        return currentJwt().getSubject();
    }

    /** Package-visible so SecurityConfig's authority converter uses the exact same parsing. */
    static Set<Role> rolesOf(Jwt jwt) {
        List<String> raw = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (raw == null) {
            return Set.of();
        }
        return raw.stream()
            .map(Role::fromClaim)
            .flatMap(Optional::stream)   // unknown role strings are silently dropped
            .collect(Collectors.toUnmodifiableSet());
    }

    private static String tenantOf(Jwt jwt) {
        String tenantId = jwt.getClaimAsString(TENANT_CLAIM);
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("JWT has no '" + TENANT_CLAIM + "' claim");
        }
        return tenantId;
    }

    private static Jwt currentJwt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            throw new IllegalStateException("No authenticated JWT in security context");
        }
        return Objects.requireNonNull(jwtAuth.getToken());
    }
}
