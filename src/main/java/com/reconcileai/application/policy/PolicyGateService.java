package com.reconcileai.application.policy;

import com.reconcileai.domain.Money;
import com.reconcileai.domain.RootCauseCategory;
import com.reconcileai.domain.RoutingDecision;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * The policy gate: a DETERMINISTIC Java decision about who must review an AI proposal. The model never decides
 * its own route; it only supplies inputs (confidence, root cause, citations) that are checked here.
 *
 * <h2>Rules, evaluated top to bottom (first match wins)</h2>
 * <pre>
 *  1. citations invalid (missing / not in retrieval snapshot)    -> MANUAL_REVIEW  CITATIONS_INVALID
 *  2. root cause UNKNOWN                                          -> MANUAL_REVIEW  UNKNOWN_ROOT_CAUSE
 *  3. confidence < 0.80                                           -> MANUAL_REVIEW  LOW_CONFIDENCE
 *  4. currency != USD (no FX table)                    -> L2_APPROVAL    NON_POLICY_CURRENCY  (fail safe)
 *  5. variance >= 25,000                                          -> L2_APPROVAL    MATERIAL_VARIANCE
 *  6. auto-resolve ENABLED && variance <= cap && conf >= min      -> AUTO_RESOLVE   WITHIN_AUTO_POLICY
 *  7. otherwise                                                   -> L1_APPROVAL    STANDARD
 * </pre>
 *
 * <h2>Materiality</h2>
 * Always the VARIANCE |ledger − external|, never the raw ledger amount. A 10,000,000 USD payment that is off by
 * 12 USD is a low-materiality break; a 30,000 USD payment booked twice is a high-materiality one.
 *
 * <h2>Signature</h2>
 * {@code evaluate(PolicyInput)} takes the computed variance, never the raw break amount.
 */
@Service
public class PolicyGateService {

    private final PolicyProperties props;

    public PolicyGateService(PolicyProperties props) {
        this.props = props;
    }

    public record PolicyInput(Money variance, BigDecimal confidence, RootCauseCategory rootCause, boolean citationsValid) {}

    public record PolicyDecision(RoutingDecision route, String reason) {}

    public PolicyDecision evaluate(PolicyInput in) {
        if (!in.citationsValid()) {
            return new PolicyDecision(RoutingDecision.MANUAL_REVIEW, "CITATIONS_INVALID");
        }
        if (in.rootCause() == RootCauseCategory.UNKNOWN) {
            return new PolicyDecision(RoutingDecision.MANUAL_REVIEW, "UNKNOWN_ROOT_CAUSE");
        }
        if (in.confidence().compareTo(props.l1MinConfidence()) < 0) {
            return new PolicyDecision(RoutingDecision.MANUAL_REVIEW, "LOW_CONFIDENCE");
        }
        if (!props.policyCurrency().equals(in.variance().currency().getCurrencyCode())) {
            return new PolicyDecision(RoutingDecision.L2_APPROVAL, "NON_POLICY_CURRENCY");
        }
        if (in.variance().isGreaterThanOrEqualTo(props.l2VarianceThreshold())) {
            return new PolicyDecision(RoutingDecision.L2_APPROVAL, "MATERIAL_VARIANCE");
        }
        PolicyProperties.AutoResolve auto = props.autoResolve();
        if (auto.enabled()
                && in.variance().amount().compareTo(auto.maxVariance()) <= 0
                && in.confidence().compareTo(auto.minConfidence()) >= 0) {
            return new PolicyDecision(RoutingDecision.AUTO_RESOLVE, "WITHIN_AUTO_POLICY");
        }
        return new PolicyDecision(RoutingDecision.L1_APPROVAL, "STANDARD");
    }

    /**
     * Re-derives the approver tier from a stored variance snapshot. ApprovalService uses this at decision time and
     * takes the STRICTER of (stored task tier, recomputed tier), so a tampered or stale task row can never lower
     * the level of human sign-off required.
     */
    public boolean requiresL2(java.math.BigDecimal varianceAmount, String currencyCode) {
        return !props.policyCurrency().equals(currencyCode)
            || varianceAmount.compareTo(props.l2VarianceThreshold()) >= 0;
    }
}
