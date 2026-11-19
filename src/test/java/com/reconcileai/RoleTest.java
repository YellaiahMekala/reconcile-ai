// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai;

import com.reconcileai.infrastructure.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit test (surefire, no Docker): role-claim parsing is the first line of RBAC defence. */
class RoleTest {

    @Test
    void acceptsBareAndPrefixedNames() {
        assertThat(Role.fromClaim("RECON_ANALYST")).contains(Role.RECON_ANALYST);
        assertThat(Role.fromClaim("ROLE_APPROVER_L2")).contains(Role.APPROVER_L2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ADMIN", "ROLE_SUPERUSER", "recon_analyst", "ROLE_", " RECON_ANALYST"})
    void rejectsUnknownOrMalformed(String raw) {
        assertThat(Role.fromClaim(raw)).isEmpty();
    }

    @Test
    void authorityHasSpringPrefix() {
        assertThat(Role.SRE_ADMIN.authority()).isEqualTo("ROLE_SRE_ADMIN");
    }
}
