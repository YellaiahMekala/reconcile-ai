// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.knowledge;

/**
 * A platform-realm service token for EKA, for the tenant a call is made on behalf of (platform Q1.1).
 * <p>
 * EKA isolates documents by the caller's identity (spike S-2): one Keycloak client per tenant gives one EKA owner per
 * tenant. A token from the wrong client would read another tenant's documents, so every failure is typed and
 * nothing is sent: {@link ServiceTokenException} with a short code.
 */
@FunctionalInterface
public interface ServiceTokenProvider {

    String token(String tenantId);

    /** A token could not be obtained or did not belong to the tenant. {@code code} is safe to store and log. */
    final class ServiceTokenException extends RuntimeException {
        private final String code;

        public ServiceTokenException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
