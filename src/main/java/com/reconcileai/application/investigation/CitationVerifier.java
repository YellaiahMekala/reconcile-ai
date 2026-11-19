// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.investigation;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifies that every chunk id the model cited was actually IN the prompt for this job.
 *
 * <h2>Why a set of ids, not a hash</h2>
 * The original design had {@code verify(citedIds, snapshotHash)}. A hash can confirm that a whole list is unchanged,
 * but it cannot answer "is THIS id a member of the snapshot?" when the model cites a subset. The job therefore persists
 * the retrieved ids, and verification is set membership.
 *
 * <h2>What it catches</h2>
 * Hallucinated ids, ids copied from injected text, ids from another break or tenant, and no citations at all.
 * Any of these routes the proposal to MANUAL_REVIEW via the policy gate.
 */
@Component
public class CitationVerifier {

    public record CitationResult(boolean valid, List<String> invalidIds) {}

    public CitationResult verify(Collection<String> citedIds, Collection<String> retrievedIds) {
        if (citedIds == null || citedIds.isEmpty()) {
            return new CitationResult(false, List.of());
        }
        Set<String> allowed = new HashSet<>(retrievedIds);
        List<String> invalid = citedIds.stream().filter(id -> !allowed.contains(id)).distinct().toList();
        return new CitationResult(invalid.isEmpty(), invalid);
    }
}
