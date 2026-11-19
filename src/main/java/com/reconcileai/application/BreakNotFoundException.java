// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application;

import java.util.UUID;

/**
 * Thrown when a break id does not exist OR belongs to another tenant. The two cases are
 * deliberately indistinguishable and both map to 404 BREAK_NOT_FOUND.
 */
public class BreakNotFoundException extends RuntimeException {
    public BreakNotFoundException(UUID id) {
        super("Break not found: " + id);
    }
}
