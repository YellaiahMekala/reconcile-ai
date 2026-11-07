package com.reconcileai.application.investigation;

import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.infrastructure.messaging.OutboxRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.application.policy.PolicyGateService.PolicyDecision;
import com.reconcileai.domain.ApprovalTask;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.InvestigationJob;
import com.reconcileai.domain.ResolutionProposal;
import com.reconcileai.domain.RoutingDecision;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder.NormalizedDraft;
import com.reconcileai.infrastructure.persistence.ApprovalTaskRepository;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.ResolutionProposalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * persistProposal(): stores the AI proposal and moves the break to its review queue.
 *
 * <p><b>This class can never resolve a break</b> (locked invariant: persistProposal() ≠ commitResolution()).
 * It only ever sets PENDING_L1, PENDING_L2 or MANUAL_REVIEW_REQUIRED, and for L1/L2 routes opens an
 * {@link ApprovalTask} for a human checker in the same transaction.
 */
@Service
public class ProposalService {

    private final ResolutionProposalRepository proposals;
    private final BreakItemRepository breaks;
    private final ApprovalTaskRepository approvalTasks;
    private final AuditRecorder audit;
    private final OutboxRecorder outbox;

    public ProposalService(ResolutionProposalRepository proposals, BreakItemRepository breaks,
                           ApprovalTaskRepository approvalTasks, AuditRecorder audit,
                           OutboxRecorder outbox) {
        this.outbox = outbox;
        this.proposals = proposals;
        this.breaks = breaks;
        this.approvalTasks = approvalTasks;
        this.audit = audit;
    }

    /** Must be called inside the runner's final transaction (MANDATORY makes that a hard requirement). */
    @Transactional(propagation = Propagation.MANDATORY)
    public ResolutionProposal persistProposal(InvestigationJob job, BreakItem breakItem, NormalizedDraft draft,
                                              boolean citationsValid, List<String> invalidCitations,
                                              PolicyDecision decision) {
        RoutingDecision route = decision.route();
        if (route == RoutingDecision.AUTO_RESOLVE) {
            // Auto-resolve is OFF (locked). Even if a config change enabled it, this build deliberately does NOT
            // wire AUTO to the approval commit path: the route is downgraded to human L1 approval. Enabling true
            // auto-commit is a separate, reviewed change (cap 1,000 USD), not a flag flip.
            route = RoutingDecision.L1_APPROVAL;
        }

        ResolutionProposal proposal = proposals.save(new ResolutionProposal(
            job.getTenantId(), breakItem.getId(), job.getId(), draft.rootCause(), draft.explanation(),
            draft.recommendedAction(), draft.confidence(), draft.citedChunkIds(), citationsValid, invalidCitations,
            route, decision.reason(), job.getInitiatedBy(), breakItem.variance()));

        breakItem.markRouted(route);
        breaks.save(breakItem);
        if (route != RoutingDecision.MANUAL_REVIEW) {
            approvalTasks.save(new ApprovalTask(proposal, route));
        }

        audit.record(new AuditEntry(job.getTenantId(), "PROPOSAL_ROUTED", "BREAK", breakItem.getId(), "system:policy-gate",
            Map.of("proposalId", proposal.getId().toString(), "route", route.name(), "reason", decision.reason(),
                "confidence", draft.confidence().toPlainString(), "citationsValid", citationsValid)));
        outbox.record(job.getTenantId(), "BREAK", breakItem.getId(), "PROPOSAL_ROUTED",
            Map.of("proposalId", proposal.getId().toString(), "route", route.name(), "reason", decision.reason()));
        return proposal;
    }
}
