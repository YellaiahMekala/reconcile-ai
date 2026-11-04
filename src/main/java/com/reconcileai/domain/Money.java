package com.reconcileai.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Value object for all monetary amounts. {@code double} is never used for money
 * anywhere in this system (NFR-04). Same-currency operations only.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    /** Absolute difference between two same-currency amounts. */
    public Money absoluteDifference(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount).abs(), this.currency);
    }

    public boolean isGreaterThanOrEqualTo(BigDecimal threshold) {
        return this.amount.compareTo(threshold) >= 0;
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "Currency mismatch: %s vs %s".formatted(this.currency, other.currency));
        }
    }
}
