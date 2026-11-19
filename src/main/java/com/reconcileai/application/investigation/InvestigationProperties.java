// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.investigation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** reconcile.investigation.* — executor sizing, orphan lease, and token budgets. */
@ConfigurationProperties("reconcile.investigation")
public record InvestigationProperties(
    int corePoolSize,
    int maxPoolSize,
    int queueCapacity,
    long leaseSeconds,
    long sweepIntervalMs,
    int maxContextTokens,
    int maxOutputTokens,
    int maxAnalystNoteChars
) {}
