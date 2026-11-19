// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

/** How a break entered the system. REST and KAFKA (intake topic) are implemented. */
public enum BreakSource {
    REST,
    KAFKA,
    MATCH_PROPOSAL
}
