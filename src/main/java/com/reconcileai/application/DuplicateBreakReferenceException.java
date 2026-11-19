// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application;

/**
 * The caller's tenant already has a break with this reference. Maps to 409.
 * References are unique per tenant, not globally.
 */
public class DuplicateBreakReferenceException extends RuntimeException {
    public DuplicateBreakReferenceException(String ref) {
        super("Break reference already exists for tenant: " + ref);
    }
}
