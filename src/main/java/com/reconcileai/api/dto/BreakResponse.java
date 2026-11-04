package com.reconcileai.api.dto;

import com.reconcileai.domain.BreakItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Break representation for API clients. Amounts are BigDecimal (serialized as JSON numbers without
 * float conversion). {@code variance} is computed by the domain, never trusted from the client.
 * tenantId is intentionally NOT exposed: callers already know their tenant from their token.
 */
public record BreakResponse(
    UUID id,
    String breakReference,
    String status,
    BigDecimal ledgerAmount,
    BigDecimal externalAmount,
    BigDecimal variance,
    String currency,
    String source,
    long version,
    Instant createdAt
) {
    public static BreakResponse from(BreakItem b) {
        return new BreakResponse(
            b.getId(), b.getBreakReference(), b.getStatus().name(),
            b.getLedgerAmount(), b.getExternalAmount(), b.variance().amount(),
            b.getCurrencyCode(), b.getSource().name(), b.getVersion(), b.getCreatedAt());
    }
}
