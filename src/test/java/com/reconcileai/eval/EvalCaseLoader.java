// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.domain.RootCauseCategory;
import com.reconcileai.domain.RoutingDecision;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads eval/cases/*.json from the test classpath and checks each case before any model is called, so a typo in a
 * case file fails fast instead of showing up as a mysterious wrong answer.
 */
public final class EvalCaseLoader {

    static final String CASES = "classpath*:eval/cases/*.json";
    /** Labelled before any run (Phase 4G): conflicting evidence, misleading notes, two causes, unsupported citations. */
    static final String HARD_CASES = "classpath*:eval/cases-hard/*.json";
    static final String EVIDENCE_DIR = "/eval/evidence/";

    private final ObjectMapper mapper = new ObjectMapper();

    public List<EvalCase> loadAll() {
        return load(CASES);
    }

    public List<EvalCase> loadHard() {
        return load(HARD_CASES);
    }

    public List<EvalCase> load(String pattern) {
        List<EvalCase> cases = new ArrayList<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                try (InputStream in = r.getInputStream()) {
                    cases.add(validate(mapper.readValue(in, EvalCase.class), r.getFilename()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        rejectDuplicateIds(cases);
        cases.sort(Comparator.comparing(EvalCase::id));
        return cases;
    }

    private EvalCase validate(EvalCase c, String file) {
        if (c.id() == null || c.id().isBlank()) {
            throw bad(file, "id is missing");
        }
        if (!file.equals(c.id() + ".json")) {
            throw bad(file, "file name must be <id>.json but the id is " + c.id());
        }
        if (c.ledgerAmount() == null || c.externalAmount() == null || c.currency() == null) {
            throw bad(file, "ledgerAmount, externalAmount and currency are required");
        }
        if (c.evidence() == null || c.evidence().isEmpty()) {
            throw bad(file, "at least one evidence file is required (the API refuses an investigation without evidence)");
        }
        for (String name : c.evidence()) {
            if (getClass().getResource(EVIDENCE_DIR + name) == null) {
                throw bad(file, "evidence file not found under eval/evidence: " + name);
            }
        }
        if (c.expected() == null) {
            throw bad(file, "expected block is missing");
        }
        try {
            RootCauseCategory.valueOf(c.expected().rootCause());
            RoutingDecision.valueOf(c.expected().route());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw bad(file, "expected.rootCause or expected.route is not a known enum value");
        }
        if (c.expected().mustCiteEvidence() == null || !c.evidence().containsAll(c.expected().mustCiteEvidence())) {
            throw bad(file, "mustCiteEvidence must be a subset of the case's evidence files");
        }
        if (c.tags() == null || c.tags().isEmpty()) {
            throw bad(file, "at least one tag is required (the report groups results by tag)");
        }
        return c;
    }

    private static void rejectDuplicateIds(List<EvalCase> cases) {
        Set<String> seen = new HashSet<>();
        for (EvalCase c : cases) {
            if (!seen.add(c.id())) {
                throw new IllegalStateException("duplicate eval case id: " + c.id());
            }
        }
    }

    private static IllegalStateException bad(String file, String why) {
        return new IllegalStateException("eval case " + file + ": " + why);
    }
}
