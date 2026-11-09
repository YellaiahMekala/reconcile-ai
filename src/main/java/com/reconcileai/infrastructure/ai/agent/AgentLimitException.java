package com.reconcileai.infrastructure.ai.agent;

/**
 * The agent ran past one of its limits (turns, tool calls or time). The message is a short code that ends up in the
 * job's failure reason, so it must stay a plain upper-case token.
 */
public class AgentLimitException extends RuntimeException {

    public static final String TIMEOUT = "AGENT_TIMEOUT";
    public static final String TURN_LIMIT = "AGENT_TURN_LIMIT";
    public static final String TOOL_LOOP = "AGENT_TOOL_LOOP";

    public AgentLimitException(String code) {
        super(code);
    }
}
