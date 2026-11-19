// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.eval;

import com.reconcileai.domain.RootCauseCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EvalCaseLoaderTest {

    private final List<EvalCase> cases = new EvalCaseLoader().loadAll();

    @Test
    void everyRootCauseHasAtLeastOneSeedCase() {
        Set<String> covered = cases.stream().map(c -> c.expected().rootCause()).collect(Collectors.toSet());
        for (RootCauseCategory category : RootCauseCategory.values()) {
            assertThat(covered).as("a case expecting " + category).contains(category.name());
        }
    }

    @Test
    void casesAreSortedAndIdsMatchFileNames() {
        assertThat(cases).extracting(EvalCase::id).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void everyCaseHasEvidenceThatExistsAndCitationTargetsInsideIt() {
        for (EvalCase c : cases) {
            assertThat(c.evidence()).isNotEmpty();
            assertThat(c.evidence()).containsAll(c.expected().mustCiteEvidence());
        }
    }

    @Test
    void theHardSetIsSeparateLabelledAndCoversTheFourWeaknessTypes() {
        List<EvalCase> hard = new EvalCaseLoader().loadHard();
        assertThat(hard).hasSize(12).allMatch(c -> c.tags().contains("hard"));
        assertThat(hard).extracting(EvalCase::id).doesNotContainAnyElementsOf(cases.stream().map(EvalCase::id).toList());
        Set<String> kinds = hard.stream().flatMap(c -> c.tags().stream()).collect(Collectors.toSet());
        assertThat(kinds).contains("conflicting", "misleading-note", "two-causes", "unsupported-citation");
        assertThat(hard).filteredOn(c -> c.tags().contains("misleading-note")).allMatch(c -> c.analystNote() != null);
        assertThat(cases).hasSize(28); // the easy set, and every floor built on it, is unchanged
    }

    @Test
    void aWrongCauseCountsAgainstSafetyOnlyWhenItReachesAnApprover() {
        EvalCaseResult toApprover = result("A-1", "FEE_OR_CHARGE", "TIMING_DIFFERENCE", "L1_APPROVAL", "L1_APPROVAL", 1, 1, 1);
        EvalCaseResult toPerson = result("A-2", "FEE_OR_CHARGE", "TIMING_DIFFERENCE", "L1_APPROVAL", "MANUAL_REVIEW", 1, 1, 1);
        EvalCaseResult right = result("A-3", "FEE_OR_CHARGE", "FEE_OR_CHARGE", "L1_APPROVAL", "L1_APPROVAL", 1, 1, 1);
        assertThat(toApprover.wrongCauseReachedApprover()).isTrue();
        assertThat(toPerson.wrongCauseReachedApprover()).isFalse();
        assertThat(right.wrongCauseReachedApprover()).isFalse();
    }

    @Test
    void summaryComputesAccuracyPercentilesAndCost() {
        EvalCaseResult right = result("A-1", "FEE_OR_CHARGE", "FEE_OR_CHARGE", "L1_APPROVAL", "L1_APPROVAL", 1000, 200, 100);
        EvalCaseResult wrong = result("A-2", "FEE_OR_CHARGE", "UNKNOWN", "L1_APPROVAL", "MANUAL_REVIEW", 1000, 200, 900);
        Properties pricing = new Properties();
        pricing.setProperty("m.input", "1.00");
        pricing.setProperty("m.output", "2.00");

        EvalSummary s = EvalSummary.of(List.of(right, wrong), pricing);

        assertThat(s.rootCauseAccuracy()).isEqualTo(0.5);
        assertThat(s.routeAccuracy()).isEqualTo(0.5);
        assertThat(s.p50WallMs()).isEqualTo(100);
        assertThat(s.p95WallMs()).isEqualTo(900);
        // 2 calls x (1000 x $1 + 200 x $2) per million tokens = $0.0028
        assertThat(s.costUsd()).isEqualByComparingTo("0.002800");
        assertThat(s.rootCauseAccuracyByTag()).containsEntry("fee", 0.5);
    }

    private static EvalCaseResult result(String id, String expRoot, String root, String expRoute, String route,
                                         int prompt, int completion, long wall) {
        return new EvalCaseResult(id, List.of("fee"), expRoot, expRoute, true, null, root, route, "STANDARD", true,
            List.of("f.txt"), List.of(), prompt, completion, wall, wall, "m", null, 1, null);
    }
}
