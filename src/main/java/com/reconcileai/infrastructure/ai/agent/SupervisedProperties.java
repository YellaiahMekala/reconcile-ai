package com.reconcileai.infrastructure.ai.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supervised mode settings (reconcile.agent.supervised.*). Off by default, and it also needs agent mode switched on,
 * because the specialists use the same chat model bean.
 *
 * <p>The three budgets are for the WHOLE run, shared by every specialist (and, in the planned variant, the
 * supervisor model itself). Adding a specialist does not add budget.
 *
 * @param maxToolCalls   read-only tool calls across all specialists
 * @param maxModelTurns  model invocations across all specialists and the supervisor
 * @param timeoutSeconds wall-clock budget for the whole run
 * @param maxDelegations planned variant only: how often the supervisor model may hand work to a specialist
 */
@ConfigurationProperties("reconcile.agent.supervised")
public record SupervisedProperties(boolean enabled, int maxToolCalls, int maxModelTurns, int timeoutSeconds,
                                   int maxDelegations) {

    public SupervisedProperties {
        maxToolCalls = maxToolCalls <= 0 ? 8 : maxToolCalls;
        maxModelTurns = maxModelTurns <= 0 ? 14 : maxModelTurns;
        timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
        maxDelegations = maxDelegations <= 0 ? 6 : maxDelegations;
    }
}
