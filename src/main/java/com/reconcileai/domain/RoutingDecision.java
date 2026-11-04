package com.reconcileai.domain;

/** Output of the policy gate. Auto-resolve exists as a route but is disabled by config (locked). */
public enum RoutingDecision {
    AUTO_RESOLVE, L1_APPROVAL, L2_APPROVAL, MANUAL_REVIEW
}
