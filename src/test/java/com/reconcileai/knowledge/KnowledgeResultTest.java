// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.knowledge;

import com.reconcileai.application.knowledge.KnowledgeHit;
import com.reconcileai.application.knowledge.KnowledgeOutcome;
import com.reconcileai.application.knowledge.KnowledgeResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeResultTest {

    private static KnowledgeHit hit(String id) {
        return new KnowledgeHit(KnowledgeHit.REF_PREFIX + id, "doc-1", "runbook.pdf", "snippet " + id);
    }

    @Test
    void okRequiresHits() {
        assertThatThrownBy(() -> KnowledgeResult.ok(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeResult.ok(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeResult(KnowledgeOutcome.OK, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonOkForbidsHits() {
        List<KnowledgeHit> hits = List.of(hit("1"));
        for (KnowledgeOutcome o : List.of(KnowledgeOutcome.EMPTY, KnowledgeOutcome.UNAVAILABLE, KnowledgeOutcome.REJECTED)) {
            assertThatThrownBy(() -> new KnowledgeResult(o, "R", hits)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void factoriesBuildTheMatchingOutcome() {
        assertThat(KnowledgeResult.empty().outcome()).isEqualTo(KnowledgeOutcome.EMPTY);
        assertThat(KnowledgeResult.unavailable("TIMEOUT").reason()).isEqualTo("TIMEOUT");
        assertThat(KnowledgeResult.rejected("ALL_HITS_SCREENED").outcome()).isEqualTo(KnowledgeOutcome.REJECTED);
        assertThat(KnowledgeResult.empty().hits()).isEmpty();
    }

    @Test
    void refsReturnsPrefixedRefsInOrder() {
        KnowledgeResult r = KnowledgeResult.ok(List.of(hit("a"), hit("b")));
        assertThat(r.refs()).containsExactly("kb:a", "kb:b").allMatch(s -> s.startsWith("kb:"));
        assertThat(KnowledgeResult.empty().refs()).isEmpty();
    }
}
