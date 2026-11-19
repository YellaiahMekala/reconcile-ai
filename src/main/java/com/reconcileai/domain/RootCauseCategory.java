// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.domain;

/** The 8 break categories the model may choose from. Anything else is coerced to UNKNOWN. */
public enum RootCauseCategory {
    TIMING_DIFFERENCE, FX_RATE_DIFFERENCE, FEE_OR_CHARGE, DUPLICATE_ENTRY, MISSING_ENTRY, DATA_ENTRY_ERROR, PARTIAL_SETTLEMENT, UNKNOWN
}
