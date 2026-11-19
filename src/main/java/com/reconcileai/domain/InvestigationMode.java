// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

/**
 * How an investigation produces its draft:
 * <ul>
 *   <li>SINGLE_CALL: one prepared prompt (default);</li>
 *   <li>AGENT: one model chooses read-only tools;</li>
 *   <li>SUPERVISED: evidence analyst, risk checker and reviewer, called in a fixed Java order (variant S1);</li>
 *   <li>SUPERVISED_PLANNED: the same specialists, with a supervisor model choosing the order (variant S2);</li>
 *   <li>RERO: the external recommendation engine (platform Q3, off by default) drafts through reconcile-ai's internal
 *       read-only tools; reconcile-ai re-checks its citations and keeps routing and approval.</li>
 * </ul>
 * Every mode ends in the same citation check, policy gate and maker-checker approval.
 */
public enum InvestigationMode {
    SINGLE_CALL, AGENT, SUPERVISED, SUPERVISED_PLANNED, RERO;

    public boolean supervised() {
        return this == SUPERVISED || this == SUPERVISED_PLANNED;
    }
}
