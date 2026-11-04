package com.reconcileai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;


/**
 * Break creation payload. Note: NO tenantId field — tenant is derived from the JWT,
 * never client-supplied. Amounts are strings to preserve BigDecimal precision on the wire.
 */
public record CreateBreakRequest(
    @NotBlank String breakReference,
    @NotNull @Pattern(regexp = "-?\\d+(\\.\\d+)?", message = "ledgerAmount must be numeric")
    String ledgerAmount,
    @NotNull @Pattern(regexp = "-?\\d+(\\.\\d+)?", message = "externalAmount must be numeric")
    String externalAmount,
    @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
    String currency
) {}
