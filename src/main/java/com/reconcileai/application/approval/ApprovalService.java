// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.approval;

import com.reconcileai.application.ConflictException;
import com.reconcileai.application.ForbiddenOperationException;
import com.reconcileai.application.NotFoundException;
import com.reconcileai.application.audit.AuditRecorder;
import com.reconcileai.application.audit.AuditRecorder.AuditEntry;
import com.reconcileai.application.policy.PolicyGateService;
import com.reconcileai.domain.ApprovalDecision;
import com.reconcileai.domain.ApprovalStatus;
import com.reconcileai.domain.ApprovalTask;
import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.ProposalStatus;
import com.reconcileai.domain.ResolutionProposal;
import com.reconcileai.infrastructure.persistence.ApprovalTaskRepository;
import com.reconcileai.infrastructure.persistence.BreakItemRepository;
import com.reconcileai.infrastructure.persistence.ResolutionProposalRepository;
import com.reconcileai.infrastructure.security.CurrentUser;
import com.reconcileai.infrastructure.security.Role;
import com.reconcileai.infrastructure.security.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Checker workflow (HITL). Order of checks in {@link #decide} is deliberate:
 * <pre>
 *  1 load task by (id, tenant from JWT)          missing / other tenant  -> 404 (never 403)
 *  2 task still OPEN, proposal PENDING_APPROVAL  already decided         -> 409
 *  3 tier: stricter of stored role and policy    L1 approver on L2 task  -> 403 INSUFFICIENT_APPROVAL_TIER
 *     re-derived from the variance snapshot
 *  4 maker-checker: JWT sub != proposal.maker    self-approval           -> 403 MAKER_CHECKER_VIOLATION
 *  5 APPROVE -> ResolutionCommitService (only path to RESOLVED) | REJECT -> break REJECTED (re-investigable)
 *  6 @Version on task/proposal/break             concurrent double click -> 409 (optimistic lock)
 * </pre>
 * Identity (subject, tenant, roles) is only ever read from the validated JWT via {@link TenantContext}. The request
 * body carries a decision and a comment; it cannot name an approver, a tenant, or a tier.
 */
@Service
public class ApprovalService {

    static final int MAX_QUEUE = 100;

    private final TenantContext tenantContext;
    private final ApprovalTaskRepository tasks;
    private final ResolutionProposalRepository proposals;
    private final BreakItemRepository breaks;
    private final PolicyGateService policy;
    private final ResolutionCommitService commitService;
    private final AuditRecorder audit;
    private final TransactionTemplate independentTx;

    public ApprovalService(TenantContext tenantContext, ApprovalTaskRepository tasks,
                           ResolutionProposalRepository proposals, BreakItemRepository breaks,
                           PolicyGateService policy, ResolutionCommitService commitService, AuditRecorder audit,
                           PlatformTransactionManager txManager) {
        this.tenantContext = tenantContext;
        this.tasks = tasks;
        this.proposals = proposals;
        this.breaks = breaks;
        this.policy = policy;
        this.commitService = commitService;
        this.audit = audit;
        // A refused self-approval throws, which rolls back the decide() transaction. The security event must
        // survive that rollback, so it is written in its own REQUIRES_NEW transaction.
        this.independentTx = new TransactionTemplate(txManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** OPEN tasks the caller's tier can act on: L1 sees L1 tasks; L2 sees L1 and L2 tasks. Oldest first. */
    @Transactional(readOnly = true)
    public List<ApprovalTask> myQueue() {
        CurrentUser user = tenantContext.currentUser();
        List<String> tiers = new ArrayList<>();
        if (user.hasRole(Role.APPROVER_L1) || user.hasRole(Role.APPROVER_L2)) {
            tiers.add(ApprovalTask.L1);
        }
        if (user.hasRole(Role.APPROVER_L2)) {
            tiers.add(ApprovalTask.L2);
        }
        if (tiers.isEmpty()) {
            return List.of();
        }
        return tasks.findByTenantIdAndStatusAndRequiredRoleInOrderByCreatedAtAsc(
                user.tenantId(), ApprovalStatus.OPEN, tiers, PageRequest.of(0, MAX_QUEUE)).stream()
            .filter(t -> !t.getMakerSubject().equals(user.subject())) // never offer a maker their own proposal
            .toList();
    }

    @Transactional(readOnly = true)
    public ApprovalTask get(UUID taskId) {
        return tasks.findByIdAndTenantId(taskId, tenantContext.requireTenantId())
            .orElseThrow(() -> new NotFoundException("APPROVAL_TASK_NOT_FOUND", "Approval task not found"));
    }

    @Transactional
    public ApprovalTask decide(UUID taskId, ApprovalDecision decision, String comment) {
        CurrentUser checker = tenantContext.currentUser();
        String tenantId = checker.tenantId();

        // 1. tenant-scoped load: another tenant's task is indistinguishable from a missing one
        ApprovalTask task = tasks.findByIdAndTenantId(taskId, tenantId)
            .orElseThrow(() -> new NotFoundException("APPROVAL_TASK_NOT_FOUND", "Approval task not found"));
        ResolutionProposal proposal = proposals.findByIdAndTenantId(task.getProposalId(), tenantId)
            .orElseThrow(() -> new NotFoundException("PROPOSAL_NOT_FOUND", "Proposal not found"));
        BreakItem breakItem = breaks.findByIdAndTenantId(task.getBreakItemId(), tenantId)
            .orElseThrow(() -> new NotFoundException("BREAK_NOT_FOUND", "Break not found"));

        // 2. state
        if (task.getStatus() != ApprovalStatus.OPEN || proposal.getStatus() != ProposalStatus.PENDING_APPROVAL) {
            throw new ConflictException("APPROVAL_ALREADY_DECIDED", "This approval task has already been decided");
        }

        // 3. tier: stricter of stored task and a fresh policy evaluation of the variance snapshot
        boolean needsL2 = task.isL2() || policy.requiresL2(proposal.getVarianceAmount(), proposal.getCurrencyCode());
        boolean tierOk = needsL2 ? checker.hasRole(Role.APPROVER_L2)
                                 : checker.hasRole(Role.APPROVER_L1) || checker.hasRole(Role.APPROVER_L2);
        if (!tierOk) {
            throw new ForbiddenOperationException("INSUFFICIENT_APPROVAL_TIER",
                needsL2 ? "This proposal requires an L2 approver" : "This proposal requires an approver role");
        }

        // 4. maker-checker: compare against the MAKER recorded on the proposal, never against decidedBy
        if (proposal.getMakerSubject().equals(checker.subject())) {
            independentTx.executeWithoutResult(s -> audit.record(new AuditEntry(tenantId, "MAKER_CHECKER_BLOCKED",
                "BREAK", breakItem.getId(), checker.subject(), Map.of("approvalTaskId", task.getId().toString()))));
            throw new ForbiddenOperationException("MAKER_CHECKER_VIOLATION",
                "The analyst who started the investigation cannot approve or reject its proposal");
        }

        // 5. apply
        String safeComment = comment == null ? null : comment.strip();
        task.decide(decision, checker.subject(), safeComment);
        if (decision == ApprovalDecision.APPROVE) {
            commitService.commitResolution(task, proposal, breakItem, checker.subject());
        } else {
            proposal.markRejected();
            breakItem.markProposalRejected();
            proposals.save(proposal);
            breaks.save(breakItem);
            tasks.save(task);
            audit.record(new AuditEntry(tenantId, "PROPOSAL_REJECTED", "BREAK", breakItem.getId(), checker.subject(),
                Map.of("proposalId", proposal.getId().toString(), "approvalTaskId", task.getId().toString(),
                    "tier", task.getRequiredRole(), "comment", safeComment == null ? "" : safeComment)));
        }
        return task;
    }
}
