package com.reconcileai.domain;

/** How a break entered the system. REST and KAFKA (intake topic) are implemented. */
public enum BreakSource {
    REST,
    KAFKA,
    MATCH_PROPOSAL
}
