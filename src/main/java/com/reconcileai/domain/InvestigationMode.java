package com.reconcileai.domain;

/** How an investigation gathers evidence: one prepared prompt, or a model that chooses read-only tools. */
public enum InvestigationMode {
    SINGLE_CALL, AGENT
}
