package com.reconcileai.infrastructure.security;

import java.util.Set;

/**
 * Immutable snapshot of the authenticated caller, built ONLY from a validated JWT.
 *
 * <p>This is what application services receive instead of raw request data. It deliberately
 * has no setters and no public way to construct it from an HTTP request - the only producer
 * is {@link TenantContext#currentUser()}.
 *
 * <p>Maker-checker rule: ("approver must not be the analyst who started the
 * investigation") compares {@code subject} values taken from here, never a client-supplied id.
 *
 * @param subject  JWT {@code sub} - the stable user id (maker/checker identity)
 * @param tenantId JWT {@code tenant_id} - the ONLY source of tenant for every query
 * @param roles    JWT {@code roles}, filtered to the known {@link Role} set
 */
public record CurrentUser(String subject, String tenantId, Set<Role> roles) {

    public CurrentUser {
        roles = Set.copyOf(roles); // defensive copy: callers cannot mutate the caller's roles
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
