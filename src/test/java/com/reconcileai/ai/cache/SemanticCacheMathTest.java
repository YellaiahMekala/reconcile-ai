// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.ai.cache;

import com.reconcileai.infrastructure.ai.cache.SemanticCacheProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticCacheMathTest {

    @Test
    void unsafeThresholdIsRefused() {
        assertThatThrownBy(() -> new SemanticCacheProperties(true, 0.5, 24, 100, "v1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticCacheProperties(true, 0.95, 24, 0, "v1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SemanticCacheProperties(false, 0.95, 24, 100, " ").namespace()).isEqualTo("v1");
    }
}
