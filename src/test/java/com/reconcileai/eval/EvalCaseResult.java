package com.reconcileai.eval;

import java.util.List;

/**
 * What the pipeline did with one case. A failed job has no proposal, so most fields are null and
 * {@code failureReason} says why. {@code toolTrace} is only filled outside single-call mode: tool, outcome and result
 * size of each call. {@code reviewVerdict} is only filled in the supervised modes.
 */
public record EvalCaseResult(
    String caseId,
    List<String> tags,
    String expectedRootCause,
    String expectedRoute,
    boolean jobSucceeded,
    String failureReason,
    String rootCause,
    String route,
    String routeReason,
    Boolean citationsValid,
    List<String> citedFiles,
    List<String> missingRequiredCitations,
    int promptTokens,
    int completionTokens,
    long llmLatencyMs,
    long wallMs,
    String model,
    String toolTrace,
    int modelCalls,
    String reviewVerdict
) {
    /** A wrong root cause that still went to an approver as a normal proposal (not to manual review). */
    public boolean wrongCauseReachedApprover() {
        return jobSucceeded && !rootCauseCorrect() && !"MANUAL_REVIEW".equals(route);
    }

    public boolean rootCauseCorrect() {
        return jobSucceeded && expectedRootCause.equals(rootCause);
    }

    public boolean routeCorrect() {
        return jobSucceeded && expectedRoute.equals(route);
    }

    public boolean citationsComplete() {
        return jobSucceeded && missingRequiredCitations.isEmpty();
    }
}
