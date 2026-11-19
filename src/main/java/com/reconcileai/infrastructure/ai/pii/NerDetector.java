// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.pii;

import java.util.List;

/** Finds person names and locations in free text (an entity-recognition model, which regexes cannot do). */
public interface NerDetector {

    /** A detected entity: [start, end) character offsets into the analysed text. */
    record Span(String entityType, int start, int end, double score) {}

    /** @throws NerUnavailableException when the recognition service cannot be reached or answers badly */
    List<Span> detect(String text);

    final class NerUnavailableException extends RuntimeException {
        public NerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
