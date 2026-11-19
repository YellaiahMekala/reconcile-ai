// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application;

/** 400: user-supplied text was rejected before any model call (e.g. injection tripwire). Zero tokens spent. */
public class InputRejectedException extends RuntimeException {

    public InputRejectedException(String message) {
        super(message);
    }
}
