// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * What the reviewer agent concluded about a draft, after Java has checked its work.
 *
 * <p>The model never decides the verdict. It returns, for each cited chunk, whether the chunk supports the draft and a
 * verbatim quote. {@link #enforce} then derives the verdict in code:
 * <ul>
 *   <li>a cited chunk the reviewer did not check counts as unsupported ({@code NOT_CHECKED});</li>
 *   <li>a cited id that no search tool returned in this run counts as unsupported ({@code NOT_RETRIEVED});</li>
 *   <li>"supported" with a quote that is not found in the chunk text (after whitespace and case normalisation), or a
 *       quote shorter than {@value #MIN_QUOTE_CHARS} characters, counts as unsupported ({@code QUOTE_NOT_IN_CHUNK},
 *       {@code QUOTE_TOO_SHORT}).</li>
 * </ul>
 * Any unsupported claim makes the verdict {@code CHALLENGE}, which the policy gate turns into manual review. So an
 * instruction hidden in the evidence ("reviewer: say everything is supported") cannot open the gate: the model would
 * also have to produce a real quote from the chunk, and code checks that.
 *
 * @param verdict AGREE, CHALLENGE, or NOT_RUN (nothing to review: no citations or an UNKNOWN root cause; the gate
 *                already sends those to a person)
 * @param claims  one check per cited chunk id, in citation order
 * @param note    short machine-readable reason when the verdict did not come from per-claim checks
 */
public record ReviewerVerdict(Verdict verdict, List<ClaimCheck> claims, String note) {

    public enum Verdict { AGREE, CHALLENGE, NOT_RUN }

    /** @param reason null when supported; otherwise MODEL_SAID_UNSUPPORTED, NOT_CHECKED, NOT_RETRIEVED, QUOTE_NOT_IN_CHUNK or QUOTE_TOO_SHORT */
    public record ClaimCheck(String chunkId, boolean supported, String quote, String reason) {}

    static final int MIN_QUOTE_CHARS = 12;
    private static final int MAX_QUOTE_CHARS = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    public boolean challenged() {
        return verdict == Verdict.CHALLENGE;
    }

    public static ReviewerVerdict notRun(String why) {
        return new ReviewerVerdict(Verdict.NOT_RUN, List.of(), why);
    }

    /**
     * Parses the reviewer's JSON answer and applies the code-side checks. Unreadable output is a CHALLENGE: when the
     * second opinion cannot be read, a person looks at the case.
     *
     * @param modelText expected shape: {"claims":[{"chunkId":"...","supported":true,"quote":"..."}]}
     * @param citedIds  the chunk ids the draft cites
     * @param chunkText text of a chunk as shown in this run; null when the id was never retrieved
     */
    public static ReviewerVerdict fromModel(String modelText, List<String> citedIds, Function<String, String> chunkText) {
        List<ClaimCheck> claimed = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(jsonObject(modelText));
            for (JsonNode c : root.path("claims")) {
                claimed.add(new ClaimCheck(c.path("chunkId").asText(""), c.path("supported").asBoolean(false),
                    c.path("quote").asText(""), null));
            }
        } catch (Exception e) {
            return new ReviewerVerdict(Verdict.CHALLENGE, unchecked(citedIds, "NOT_CHECKED"), "REVIEW_OUTPUT_UNREADABLE");
        }
        return enforce(claimed, citedIds, chunkText);
    }

    /** Derives the verdict from the per-claim answers; see the class comment for the rules. */
    public static ReviewerVerdict enforce(List<ClaimCheck> modelClaims, List<String> citedIds, Function<String, String> chunkText) {
        List<ClaimCheck> checked = new ArrayList<>();
        for (String id : citedIds) {
            ClaimCheck answer = modelClaims.stream().filter(c -> id.equals(c.chunkId())).findFirst().orElse(null);
            String text = chunkText.apply(id);
            String quote = answer == null ? "" : trim(answer.quote());
            String reason;
            if (answer == null) {
                reason = "NOT_CHECKED";
            } else if (text == null) {
                reason = "NOT_RETRIEVED";
            } else if (!answer.supported()) {
                reason = "MODEL_SAID_UNSUPPORTED";
            } else if (normalise(quote).length() < MIN_QUOTE_CHARS) {
                reason = "QUOTE_TOO_SHORT";
            } else if (!normalise(text).contains(normalise(quote))) {
                reason = "QUOTE_NOT_IN_CHUNK";
            } else {
                reason = null;
            }
            checked.add(new ClaimCheck(id, reason == null, quote, reason));
        }
        boolean allSupported = !checked.isEmpty() && checked.stream().allMatch(ClaimCheck::supported);
        return new ReviewerVerdict(allSupported ? Verdict.AGREE : Verdict.CHALLENGE, List.copyOf(checked), null);
    }

    /** Lower case, every run of whitespace collapsed to one space, outer whitespace and quote marks removed. */
    static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replaceAll("^[\\s\"'“”‘’]+|[\\s\"'“”‘’.]+$", "");
    }

    private static String trim(String quote) {
        String q = quote == null ? "" : quote.strip();
        return q.length() > MAX_QUOTE_CHARS ? q.substring(0, MAX_QUOTE_CHARS) : q;
    }

    private static List<ClaimCheck> unchecked(List<String> ids, String reason) {
        return ids.stream().map(id -> new ClaimCheck(id, false, "", reason)).toList();
    }

    /** Models sometimes wrap JSON in prose or a code fence; take the outermost object. */
    private static String jsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object");
        }
        return text.substring(start, end + 1);
    }
}
