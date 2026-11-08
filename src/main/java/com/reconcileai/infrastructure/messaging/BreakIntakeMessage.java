package com.reconcileai.infrastructure.messaging;

/** Wire format on the intake topic. Amounts are strings (no binary floating point on the wire). */
public record BreakIntakeMessage(String tenantId, String breakReference, String ledgerAmount, String externalAmount,
                                 String currency) {}
