// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.guard;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Layer 1 of prompt-injection defence: a cheap, deterministic scan of UNTRUSTED text
 * (evidence chunks at ingestion time, analyst notes at investigation time).
 *
 * <h2>What it is / is not</h2>
 * A tripwire for obvious attacks ("ignore previous instructions", fake role tags). It is NOT a
 * complete defence and is not presented as one: subtle injections will get past it. That is why
 * later layers exist and are independently tested - untrusted-data delimiting in the prompt,
 * strict output schema, citation verification, the policy gate, and human approval. The model
 * also has NO tools, so even a fully "hijacked" model can only produce a proposal a human reviews.
 *
 * <h2>Why quarantine instead of reject the upload?</h2>
 * A hostile line inside a genuine bank statement is still evidence a human may need to see.
 * Suspect chunks are stored with {@code injectionSuspect=true} and excluded from LLM context only.
 */
@Component
public class InjectionScanner {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Rule> RULES = List.of(
        new Rule("IGNORE_INSTRUCTIONS",
            Pattern.compile("\\b(ignore|disregard|forget)\\b.{0,30}\\b(previous|prior|above|all|earlier|system)\\b.{0,20}\\b(instructions?|prompts?|rules?)\\b", FLAGS)),
        new Rule("ROLE_REASSIGNMENT",
            Pattern.compile("\\byou are now\\b|\\bact as (an?|the) (system|admin|developer)\\b|\\bnew instructions\\b", FLAGS)),
        new Rule("PROMPT_EXFILTRATION",
            Pattern.compile("\\b(reveal|print|show|repeat)\\b.{0,20}\\b(system prompt|hidden instructions|your instructions)\\b", FLAGS)),
        new Rule("FAKE_ROLE_TAG",
            Pattern.compile("</?\\s*(system|assistant|developer)\\s*>|\\[/?(SYSTEM|INST)\\]|###\\s*(system|instruction)", FLAGS)),
        new Rule("DELIMITER_ESCAPE",
            Pattern.compile("<<<\\s*END[_ ]EVIDENCE|END OF EVIDENCE", FLAGS))
    );

    /** @return the first matched rule name, or empty if the text looks clean. */
    public Optional<String> scan(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return RULES.stream().filter(r -> r.pattern().matcher(text).find()).map(Rule::name).findFirst();
    }

    public boolean isSuspect(String text) {
        return scan(text).isPresent();
    }

    private record Rule(String name, Pattern pattern) {}
}
