package com.reconcileai.infrastructure.ai.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent mode settings (reconcile.agent.*). Off by default.
 *
 * @param maxToolCalls       tool calls the model may make in one run; further calls get a "limit reached" answer
 * @param maxModelTurns      model invocations in one run (each tool round trip is one turn); more than this fails the job
 * @param timeoutSeconds     wall-clock budget for the whole run
 * @param maxToolResultChars a tool result longer than this is cut before the model sees it
 */
@ConfigurationProperties("reconcile.agent")
public record AgentProperties(boolean enabled, int maxToolCalls, int maxModelTurns, int timeoutSeconds, int maxToolResultChars) {}
