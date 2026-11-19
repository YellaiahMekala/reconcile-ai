// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.investigation;

import com.reconcileai.application.NotFoundException;
import com.reconcileai.infrastructure.persistence.InvestigationJobRepository;
import com.reconcileai.infrastructure.security.TenantContext;
import com.reconcileai.infrastructure.streaming.SseProgressBroadcaster;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Opens a progress stream. Order matters:
 * <ol>
 *   <li>tenant-scoped existence check on the request thread (foreign or unknown job -> 404, no stream opened)</li>
 *   <li>register the emitter BEFORE reading the snapshot, so no transition can fall between the two</li>
 *   <li>re-read and send the snapshot; if already terminal, the stream completes immediately</li>
 * </ol>
 * A client may therefore see one stage twice; events are idempotent (status/stage, not deltas).
 */
@Service
public class InvestigationEventsService {

    private final TenantContext tenantContext;
    private final InvestigationJobRepository jobs;
    private final SseProgressBroadcaster broadcaster;

    public InvestigationEventsService(TenantContext tenantContext, InvestigationJobRepository jobs,
                                      SseProgressBroadcaster broadcaster) {
        this.tenantContext = tenantContext;
        this.jobs = jobs;
        this.broadcaster = broadcaster;
    }

    public SseEmitter subscribe(UUID jobId) {
        String tenantId = tenantContext.requireTenantId();
        jobs.findByIdAndTenantId(jobId, tenantId)
            .orElseThrow(() -> new NotFoundException("JOB_NOT_FOUND", "Investigation job not found"));
        SseEmitter emitter = broadcaster.subscribe(jobId, tenantId);
        jobs.findByIdAndTenantId(jobId, tenantId).ifPresent(job -> broadcaster.sendSnapshot(emitter, job));
        return emitter;
    }
}
