// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gateway-supplied codes must fit the column and be plain codes, or the row (and its invocation id) would be lost. */
class JpaGatewayCallLogTest {

    @Test
    void keepsPlainCodesAndReplacesAnythingElse() {
        assertThat(JpaGatewayCallLog.safe("UNKNOWN_OUTCOME", 48)).isEqualTo("UNKNOWN_OUTCOME");
        assertThat(JpaGatewayCallLog.safe(null, 48)).isNull();
        assertThat(JpaGatewayCallLog.safe("A".repeat(49), 48)).isEqualTo("UNMAPPED");
        assertThat(JpaGatewayCallLog.safe("bad code; DROP", 48)).isEqualTo("UNMAPPED");
        assertThat(JpaGatewayCallLog.safe("OK", 32)).isEqualTo("OK");
    }
}
