// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.investigation;

import com.reconcileai.application.investigation.CitationVerifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationVerifierTest {

    private final CitationVerifier verifier = new CitationVerifier();

    @Test
    void subsetOfSnapshotIsValid() {
        assertThat(verifier.verify(List.of("b"), List.of("a", "b", "c")).valid()).isTrue();
    }

    @Test
    void fabricatedIdIsInvalidAndReported() {
        var r = verifier.verify(List.of("a", "ghost"), List.of("a", "b"));
        assertThat(r.valid()).isFalse();
        assertThat(r.invalidIds()).containsExactly("ghost");
    }

    @Test
    void noCitationsIsInvalid() {
        assertThat(verifier.verify(List.of(), List.of("a")).valid()).isFalse();
    }
}
