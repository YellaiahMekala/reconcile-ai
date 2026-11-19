// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.ai;

import com.reconcileai.infrastructure.ai.retrieval.RrfFusion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RrfFusionTest {

    @Test
    void chunkFoundByBothLegsOutranksSingleLegTopHit() {
        Map<String, Double> s = RrfFusion.fuse(List.of(List.of("v1", "both"), List.of("k1", "both")), 60);
        // "both" = 1/62 + 1/62 = 0.03226 ; "v1" = 1/61 = 0.01639
        assertThat(s.get("both")).isGreaterThan(s.get("v1")).isGreaterThan(0.0);
        assertThat(s.get("both")).isCloseTo(2.0 / 62, within(1e-9));
    }

    @Test
    void emptyListsAreHarmless() {
        assertThat(RrfFusion.fuse(List.of(List.of(), List.of("x")), 60)).containsOnlyKeys("x");
    }
}
