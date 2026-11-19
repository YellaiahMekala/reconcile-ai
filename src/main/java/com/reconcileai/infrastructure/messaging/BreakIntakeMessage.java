// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.messaging;

/** Wire format on the intake topic. Amounts are strings (no binary floating point on the wire). */
public record BreakIntakeMessage(String tenantId, String breakReference, String ledgerAmount, String externalAmount,
                                 String currency) {}
