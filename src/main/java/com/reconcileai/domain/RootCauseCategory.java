package com.reconcileai.domain;

/** The 8 break categories the model may choose from. Anything else is coerced to UNKNOWN. */
public enum RootCauseCategory {
    TIMING_DIFFERENCE, FX_RATE_DIFFERENCE, FEE_OR_CHARGE, DUPLICATE_ENTRY, MISSING_ENTRY, DATA_ENTRY_ERROR, PARTIAL_SETTLEMENT, UNKNOWN
}
