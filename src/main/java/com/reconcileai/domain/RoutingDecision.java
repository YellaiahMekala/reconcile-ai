// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

/** Output of the policy gate. Auto-resolve exists as a route but is disabled by config (locked). */
public enum RoutingDecision {
    AUTO_RESOLVE, L1_APPROVAL, L2_APPROVAL, MANUAL_REVIEW
}
