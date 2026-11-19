// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.eval;

import java.util.List;

/**
 * One labelled break for the evaluation harness. The expected block is what a careful human reviewer would
 * decide, not what any particular model happens to answer. {@code analystNote} is optional; the hard set uses it to
 * test a misleading hint from the analyst.
 */
public record EvalCase(
    String id,
    String ledgerAmount,
    String externalAmount,
    String currency,
    List<String> evidence,
    Expected expected,
    List<String> tags,
    String analystNote
) {

    /**
     * @param rootCause        a RootCauseCategory name
     * @param route            a RoutingDecision name
     * @param mustCiteEvidence file names the proposal has to cite (empty when no answer is expected)
     */
    public record Expected(String rootCause, String route, List<String> mustCiteEvidence) {}
}
