package com.reconcileai.infrastructure.streaming;

import com.reconcileai.domain.InvestigationJob;

import java.time.Instant;
import java.util.UUID;

/**
 * The ONLY payload that is streamed. Lifecycle metadata, never model output: the proposal is validated server-side
 * (citations + policy) and fetched over REST once {@code proposalId} appears. Streaming raw tokens would show
 * approvers unverified text.
 */
public record ProgressEvent(UUID jobId, UUID breakItemId, String status, String stage, UUID proposalId,
                            String failureReason, boolean terminal, Instant at) {

    public static ProgressEvent of(InvestigationJob j) {
        return new ProgressEvent(j.getId(), j.getBreakItemId(), j.getStatus().name(), j.getStage().name(),
            j.getProposalId(), j.getFailureReason(), j.isTerminal(), Instant.now());
    }
}
