package com.reconcileai.application.investigation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** reconcile.investigation.* — executor sizing, orphan lease, and token budgets. */
@ConfigurationProperties("reconcile.investigation")
public record InvestigationProperties(
    int corePoolSize,
    int maxPoolSize,
    int queueCapacity,
    long leaseSeconds,
    long sweepIntervalMs,
    int maxContextTokens,
    int maxOutputTokens,
    int maxAnalystNoteChars
) {}
