package com.reconcileai.application.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Policy thresholds (reconcile.policy.*). Values are fixed hard limits; the compact constructor fails application
 * startup if configuration tries to loosen the hard limits, so a mistyped YAML value cannot quietly widen
 * auto-resolution.
 */
@ConfigurationProperties("reconcile.policy")
public record PolicyProperties(
    BigDecimal l1MinConfidence,
    BigDecimal l2VarianceThreshold,
    String policyCurrency,
    AutoResolve autoResolve
) {
    /** Hard ceiling from the locked decisions. Config may lower it, never raise it. */
    public static final BigDecimal AUTO_RESOLVE_HARD_CAP = new BigDecimal("1000");

    public PolicyProperties {
        if (l1MinConfidence.compareTo(new BigDecimal("0.80")) < 0) {
            throw new IllegalStateException("reconcile.policy.l1-min-confidence may not be below 0.80");
        }
        if (autoResolve.maxVariance().compareTo(AUTO_RESOLVE_HARD_CAP) > 0) {
            throw new IllegalStateException("reconcile.policy.auto-resolve.max-variance may not exceed " + AUTO_RESOLVE_HARD_CAP);
        }
    }

    public record AutoResolve(boolean enabled, BigDecimal maxVariance, BigDecimal minConfidence) {}
}
