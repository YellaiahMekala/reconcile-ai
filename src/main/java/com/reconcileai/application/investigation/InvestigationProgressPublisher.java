package com.reconcileai.application.investigation;

import com.reconcileai.domain.InvestigationJob;

/**
 * Port for job lifecycle notifications, called AFTER each stage commits.
 * Carries status/stage only, never model output: the decision-critical proposal is validated server-side
 * and read via REST once complete. Clients receive progress over SSE.
 */
public interface InvestigationProgressPublisher {

    void publish(InvestigationJob job);
}
