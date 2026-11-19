// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.cache;

import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder.BuiltPrompt;
import com.reconcileai.infrastructure.ai.prompt.ProposalDraft;

import java.util.Optional;

/**
 * Cache of model drafts for investigation prompts. A hit replaces ONLY the model call: the draft still goes through
 * normalisation, citation verification and the policy gate, exactly like a fresh one.
 */
public interface SemanticCache {

    enum HitType { EXACT, SEMANTIC }

    record Hit(ProposalDraft draft, HitType type, double similarity) {}

    Optional<Hit> lookup(String tenantId, BuiltPrompt prompt, String analystNote);

    void store(String tenantId, BuiltPrompt prompt, ProposalDraft draft);

    /** Used when reconcile.ai.cache.enabled=false. */
    SemanticCache DISABLED = new SemanticCache() {
        @Override
        public Optional<Hit> lookup(String tenantId, BuiltPrompt prompt, String analystNote) {
            return Optional.empty();
        }

        @Override
        public void store(String tenantId, BuiltPrompt prompt, ProposalDraft draft) {
            // no-op
        }
    };
}
