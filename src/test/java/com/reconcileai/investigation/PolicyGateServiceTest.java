// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.investigation;

import com.reconcileai.application.policy.PolicyGateService;
import com.reconcileai.application.policy.PolicyGateService.PolicyInput;
import com.reconcileai.application.policy.PolicyProperties;
import com.reconcileai.domain.Money;
import com.reconcileai.domain.RootCauseCategory;
import com.reconcileai.domain.RoutingDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Boundary tests for the policy thresholds. Exact-boundary values are the ones that break in production. */
class PolicyGateServiceTest {

    private static PolicyProperties props(boolean autoEnabled) {
        return new PolicyProperties(new BigDecimal("0.80"), new BigDecimal("25000"), "USD",
            new PolicyProperties.AutoResolve(autoEnabled, new BigDecimal("1000"), new BigDecimal("0.95")));
    }

    private final PolicyGateService gate = new PolicyGateService(props(false));

    private static PolicyInput in(String variance, String currency, String confidence, RootCauseCategory cause, boolean citations) {
        return new PolicyInput(Money.of(variance, currency), new BigDecimal(confidence), cause, citations);
    }

    @ParameterizedTest(name = "variance={0} confidence={1} -> {2}")
    @CsvSource({
        "500.00,   0.800, L1_APPROVAL",      // confidence exactly at L1 minimum is enough
        "500.00,   0.799, MANUAL_REVIEW",    // just below -> manual
        "24999.99, 0.900, L1_APPROVAL",
        "25000.00, 0.900, L2_APPROVAL",      // variance exactly at threshold -> L2
        "25000.01, 0.900, L2_APPROVAL",
        "0.00,     0.990, L1_APPROVAL"       // auto-resolve disabled: even trivial breaks need a human
    })
    void thresholds(String variance, String confidence, RoutingDecision expected) {
        assertThat(gate.evaluate(in(variance, "USD", confidence, RootCauseCategory.FEE_OR_CHARGE, true)).route()).isEqualTo(expected);
    }

    @Test
    void invalidCitationsAlwaysManualEvenWithHighConfidence() {
        var d = gate.evaluate(in("10.00", "USD", "0.99", RootCauseCategory.TIMING_DIFFERENCE, false));
        assertThat(d.route()).isEqualTo(RoutingDecision.MANUAL_REVIEW);
        assertThat(d.reason()).isEqualTo("CITATIONS_INVALID");
    }

    @Test
    void unknownRootCauseIsManual() {
        assertThat(gate.evaluate(in("10.00", "USD", "0.95", RootCauseCategory.UNKNOWN, true)).reason()).isEqualTo("UNKNOWN_ROOT_CAUSE");
    }

    @Test
    void nonPolicyCurrencyFailsSafeToL2() {
        assertThat(gate.evaluate(in("10.00", "EUR", "0.95", RootCauseCategory.FEE_OR_CHARGE, true)).route())
            .isEqualTo(RoutingDecision.L2_APPROVAL);
    }

    @Test
    void autoResolveOnlyWhenEnabledAndWithinCap() {
        PolicyGateService enabled = new PolicyGateService(props(true));
        assertThat(enabled.evaluate(in("1000.00", "USD", "0.95", RootCauseCategory.FEE_OR_CHARGE, true)).route())
            .isEqualTo(RoutingDecision.AUTO_RESOLVE);
        assertThat(enabled.evaluate(in("1000.01", "USD", "0.99", RootCauseCategory.FEE_OR_CHARGE, true)).route())
            .isEqualTo(RoutingDecision.L1_APPROVAL);
        assertThat(enabled.evaluate(in("10.00", "USD", "0.94", RootCauseCategory.FEE_OR_CHARGE, true)).route())
            .isEqualTo(RoutingDecision.L1_APPROVAL);
    }

    @Test
    void configCannotLoosenLockedLimits() {
        assertThatThrownBy(() -> new PolicyProperties(new BigDecimal("0.80"), new BigDecimal("25000"), "USD",
            new PolicyProperties.AutoResolve(true, new BigDecimal("5000"), new BigDecimal("0.95"))))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PolicyProperties(new BigDecimal("0.50"), new BigDecimal("25000"), "USD",
            new PolicyProperties.AutoResolve(false, new BigDecimal("1000"), new BigDecimal("0.95"))))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reviewerChallengeAlwaysMeansManualReviewAndIsCheckedFirst() {
        // a draft that would otherwise go to L1, and one that would go to L2
        PolicyGateService auto = new PolicyGateService(props(true));
        for (PolicyGateService g : java.util.List.of(gate, auto)) {
            var d = g.evaluate(new PolicyInput(Money.of("10.00", "USD"), new BigDecimal("0.99"),
                RootCauseCategory.FEE_OR_CHARGE, true, true));
            assertThat(d.route()).isEqualTo(RoutingDecision.MANUAL_REVIEW);
            assertThat(d.reason()).isEqualTo("REVIEWER_CHALLENGE");
        }
        var material = gate.evaluate(new PolicyInput(Money.of("90000.00", "EUR"), new BigDecimal("0.99"),
            RootCauseCategory.FEE_OR_CHARGE, false, true));
        assertThat(material.reason()).isEqualTo("REVIEWER_CHALLENGE");
    }

    @Test
    void noChallengeChangesNothing() {
        PolicyInput withFlag = new PolicyInput(Money.of("500.00", "USD"), new BigDecimal("0.90"), RootCauseCategory.FEE_OR_CHARGE, true, false);
        PolicyInput legacy = in("500.00", "USD", "0.90", RootCauseCategory.FEE_OR_CHARGE, true);
        assertThat(gate.evaluate(withFlag)).isEqualTo(gate.evaluate(legacy));
        assertThat(gate.evaluate(withFlag).route()).isEqualTo(RoutingDecision.L1_APPROVAL);
    }
}
