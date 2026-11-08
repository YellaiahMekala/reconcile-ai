package com.reconcileai.infrastructure.ai.pii;

import com.reconcileai.infrastructure.ai.pii.NerDetector.Span;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replaces entity spans reported by a {@link NerDetector} with placeholders such as {@code [PERSON]}. */
public final class NerMasker {

    /** Safe text plus how many entities of each type were replaced. */
    public record Result(String text, Map<String, Integer> counts) {}

    private NerMasker() {}

    public static Result apply(String text, List<Span> spans) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (spans.isEmpty()) {
            return new Result(text, counts);
        }
        // Right to left so earlier offsets stay valid; drop spans overlapping one already applied; ignore bad offsets.
        List<Span> ordered = spans.stream()
            .filter(s -> s.start() >= 0 && s.end() > s.start() && s.end() <= text.length())
            .sorted(Comparator.comparingInt(Span::start).reversed())
            .toList();
        StringBuilder sb = new StringBuilder(text);
        int boundary = Integer.MAX_VALUE;
        for (Span s : ordered) {
            if (s.end() > boundary) {
                continue;
            }
            sb.replace(s.start(), s.end(), "[" + s.entityType() + "]");
            boundary = s.start();
            counts.merge(s.entityType(), 1, Integer::sum);
        }
        return new Result(sb.toString(), counts);
    }
}
