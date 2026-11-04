package com.reconcileai;

import com.reconcileai.domain.BreakItem;
import com.reconcileai.domain.BreakSource;
import com.reconcileai.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests (surefire, no Docker) for the money model.
 * Proves: BigDecimal precision is preserved, mixed currencies are rejected, and
 * variance = abs(ledger - external) - the materiality basis for the policy gate.
 */
class MoneyAndVarianceTest {

    @Test
    void varianceIsAbsoluteDifferenceNotLedger() {
        BreakItem item = new BreakItem("TENANT_A", "BRK-1",
            Money.of("50000.00", "USD"), Money.of("50500.00", "USD"), BreakSource.REST);
        // variance is |50000 - 50500| = 500, NOT the raw ledger 50000
        assertThat(item.variance().amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void mismatchedCurrencyRejected() {
        assertThatThrownBy(() -> new BreakItem("TENANT_A", "BRK-2",
            Money.of("1.00", "USD"), Money.of("1.00", "EUR"), BreakSource.REST))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thresholdComparison() {
        Money variance = Money.of("25000.00", "USD");
        assertThat(variance.isGreaterThanOrEqualTo(new BigDecimal("25000"))).isTrue();
        assertThat(variance.isGreaterThanOrEqualTo(new BigDecimal("25000.01"))).isFalse();
    }
}
