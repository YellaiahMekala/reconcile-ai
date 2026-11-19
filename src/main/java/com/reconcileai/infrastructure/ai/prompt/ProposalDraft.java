// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.prompt;

import java.math.BigDecimal;
import java.util.List;

/**
 * The ONLY shape the model is allowed to return. BeanOutputConverter derives a JSON schema from this record and
 * appends it to the system prompt; the response is parsed back into it. Anything that does not parse fails the job
 * (retryable) instead of being guessed at.
 *
 * <p>Values are UNTRUSTED until {@link InvestigationPromptBuilder#normalize} clamps and coerces them.
 */
public record ProposalDraft(
    String rootCause,
    String explanation,
    String recommendedAction,
    BigDecimal confidence,
    List<String> citedChunkIds
) {}
