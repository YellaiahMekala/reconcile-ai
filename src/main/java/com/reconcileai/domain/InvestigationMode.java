package com.reconcileai.domain;

/**
 * How an investigation produces its draft:
 * <ul>
 *   <li>SINGLE_CALL: one prepared prompt (default);</li>
 *   <li>AGENT: one model chooses read-only tools;</li>
 *   <li>SUPERVISED: evidence analyst, risk checker and reviewer, called in a fixed Java order (variant S1);</li>
 *   <li>SUPERVISED_PLANNED: the same specialists, with a supervisor model choosing the order (variant S2).</li>
 * </ul>
 * Every mode ends in the same citation check, policy gate and maker-checker approval.
 */
public enum InvestigationMode {
    SINGLE_CALL, AGENT, SUPERVISED, SUPERVISED_PLANNED;

    public boolean supervised() {
        return this == SUPERVISED || this == SUPERVISED_PLANNED;
    }
}
