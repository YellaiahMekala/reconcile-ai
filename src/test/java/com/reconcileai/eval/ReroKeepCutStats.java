// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.eval;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Statistics fixed by docs/adr/RERO_EVALUATION_RULE.md section 3.3 (platform Q3.7): 95% Wilson score interval, exact
 * two-sided McNemar (binomial) p-value on the discordant pairs, and nearest-rank percentile. Pure functions, unit
 * tested in {@code ReroKeepCutStatsTest}.
 */
final class ReroKeepCutStats {

    /** z for a two-sided 95% interval. */
    static final double Z95 = 1.959963984540054;

    record Interval(double low, double high) {
        String percent() {
            return String.format(java.util.Locale.ROOT, "%.1f%% to %.1f%%", low * 100, high * 100);
        }
    }

    private ReroKeepCutStats() {
    }

    /** 95% Wilson score interval for k successes out of n. n = 0 gives [0, 1]. */
    static Interval wilson(int k, int n) {
        if (n < 0 || k < 0 || k > n) {
            throw new IllegalArgumentException("need 0 <= k <= n");
        }
        if (n == 0) {
            return new Interval(0, 1);
        }
        double p = (double) k / n;
        double z2 = Z95 * Z95;
        double denom = 1 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denom;
        double half = Z95 * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        // At k = 0 and k = n the bound is exactly 0 or 1; floating point would leave 1e-17 noise.
        return new Interval(k == 0 ? 0 : Math.max(0, centre - half), k == n ? 1 : Math.min(1, centre + half));
    }

    /**
     * Exact two-sided McNemar test: with b and c discordant pairs, p = min(1, 2 * P(X <= min(b, c))) for
     * X ~ Binomial(b + c, 0.5). No discordant pairs gives p = 1.
     */
    static double mcnemarExact(int b, int c) {
        if (b < 0 || c < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        int n = b + c;
        if (n == 0) {
            return 1.0;
        }
        int k = Math.min(b, c);
        BigDecimal tail = BigDecimal.ZERO;
        BigDecimal binom = BigDecimal.ONE; // C(n, 0)
        for (int i = 0; i <= k; i++) {
            if (i > 0) {
                binom = binom.multiply(BigDecimal.valueOf(n - i + 1)).divide(BigDecimal.valueOf(i), MathContext.DECIMAL128);
            }
            tail = tail.add(binom);
        }
        double p = tail.multiply(BigDecimal.valueOf(2)).divide(BigDecimal.valueOf(2).pow(n), MathContext.DECIMAL128)
            .doubleValue();
        return Math.min(1.0, p);
    }

    /** Nearest-rank percentile (q in (0, 100]): the value at rank ceil(q / 100 * n) of the sorted list. */
    static long nearestRank(List<Long> values, double q) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("no values");
        }
        if (q <= 0 || q > 100) {
            throw new IllegalArgumentException("q must be in (0, 100]");
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(q / 100.0 * sorted.size());
        return sorted.get(Math.max(1, rank) - 1);
    }
}
