// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
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
