// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Optional manual scores from eval/human-scores.csv (header: case_id,clarity,faithfulness,notes). Rows with an empty
 * score are skipped, so the file can be filled in gradually. Both scores run from 1 (poor) to 5 (excellent).
 */
public record HumanScores(int scoredCases, double avgClarity, double avgFaithfulness) {

    public static HumanScores load(Path csv) {
        if (!Files.isRegularFile(csv)) {
            return new HumanScores(0, 0, 0);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(csv);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<int[]> scored = lines.stream().skip(1).map(l -> l.split(",", -1))
            .filter(p -> p.length >= 3 && isScore(p[1]) && isScore(p[2]))
            .map(p -> new int[] {Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())})
            .toList();
        OptionalDouble clarity = scored.stream().mapToInt(s -> s[0]).average();
        OptionalDouble faithfulness = scored.stream().mapToInt(s -> s[1]).average();
        return new HumanScores(scored.size(), clarity.orElse(0), faithfulness.orElse(0));
    }

    private static boolean isScore(String s) {
        return s.trim().matches("[1-5]");
    }
}
