// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: {@code score(d) = Σ 1 / (k + rank_i(d))} over each ranked list containing d.
 *
 * <p>Why RRF instead of adding raw scores: cosine similarity (0..1) and ts_rank (unbounded) are on
 * different scales. RRF uses only RANK positions, so no score normalisation or weight tuning is needed,
 * and a chunk found by BOTH legs naturally rises to the top. k=60 is the value from the original paper.
 */
public final class RrfFusion {

    private RrfFusion() {}

    /**
     * @param rankedLists each list is ordered best-first and contains ids
     * @return id -> fused score, iteration order = first time each id was seen
     */
    public static Map<String, Double> fuse(List<List<String>> rankedLists, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                scores.merge(list.get(i), 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores;
    }
}
