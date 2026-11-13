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
