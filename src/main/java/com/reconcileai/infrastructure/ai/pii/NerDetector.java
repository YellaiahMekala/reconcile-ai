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
