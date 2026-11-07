package com.reconcileai.application.approval;

import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.infrastructure.messaging.OutboxRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.domain.ApprovalTask;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.ResolutionProposal;
import com.reconcileai.infrastructure.persistence.ApprovalTaskRepository;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.ResolutionProposalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * commitResolution(): the ONLY code path that moves a break to RESOLVED (locked invariant).
 *
 * <ul>
 *   <li><b>Package-private</b> class and method: only {@link ApprovalService}, after every checker rule has passed,
 *       can reach it. Controllers, the investigation runner and ProposalService cannot.</li>
 *   <li>{@code Propagation.MANDATORY}: must run inside ApprovalService's transaction, so break, proposal, task and
 *       audit row commit together or not at all.</li>
 *   <li>ResolutionPathArchitectureTest fails the build if {@code markResolved()} is called anywhere else.</li>
 * </ul>
 * Persisting a proposal (ProposalService) and committing a resolution (here) are deliberately different classes in
 * different packages: an AI output can never become a resolution without this step.
 */
@Service
class ResolutionCommitService {

    private final BreakItemRepository breaks;
    private final ResolutionProposalRepository proposals;
    private final ApprovalTaskRepository tasks;
    private final AuditRecorder audit;
    private final OutboxRecorder outbox;

    ResolutionCommitService(BreakItemRepository breaks, ResolutionProposalRepository proposals,
                            ApprovalTaskRepository tasks, AuditRecorder audit,
                            OutboxRecorder outbox) {
        this.outbox = outbox;
        this.breaks = breaks;
        this.proposals = proposals;
        this.tasks = tasks;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void commitResolution(ApprovalTask approvedTask, ResolutionProposal proposal, BreakItem breakItem,
                          String checkerSubject) {
        proposal.markApproved();
        breakItem.markResolved();
        proposals.save(proposal);
        breaks.save(breakItem);
        tasks.save(approvedTask);
        audit.record(new AuditEntry(breakItem.getTenantId(), "RESOLUTION_COMMITTED", "BREAK", breakItem.getId(),
            checkerSubject, Map.of(
                "proposalId", proposal.getId().toString(),
                "approvalTaskId", approvedTask.getId().toString(),
                "tier", approvedTask.getRequiredRole(),
                "maker", proposal.getMakerSubject(),
                "rootCause", proposal.getRootCause().name(),
                "variance", proposal.getVarianceAmount().toPlainString() + " " + proposal.getCurrencyCode())));
        outbox.record(breakItem.getTenantId(), "BREAK", breakItem.getId(), "BREAK_RESOLVED", Map.of(
            "proposalId", proposal.getId().toString(), "tier", approvedTask.getRequiredRole()));
    }
}
