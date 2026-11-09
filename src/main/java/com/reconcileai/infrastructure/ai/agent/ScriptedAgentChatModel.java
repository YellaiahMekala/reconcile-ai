package com.reconcileai.infrastructure.ai.agent;

import com.reconcileai.infrastructure.ai.llm.MockLlmChatModel;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic stand-in for a tool-calling model, used under the mock-llm profile and in tests. It follows a fixed
 * script: look at the break, search the evidence, then answer with the same keyword rules as {@link MockLlmChatModel}
 * applied to what the search returned. It therefore exercises the real agent loop, tool guards, accounting and
 * citation check with no network and no cost.
 *
 * <p>Fault injection: if any tool result contains "simulate agent loop" the model keeps calling the search tool
 * forever, which lets a test prove that the turn and tool-call limits stop a runaway model.
 */
public class ScriptedAgentChatModel implements ChatModel {

    public static final String MODEL_NAME = "mock-agent";
    private static final String SEARCH_QUERY = "fee timing settlement value date exchange rate duplicate missing partial payment";

    private final AtomicInteger callIds = new AtomicInteger();

    @Override
    public ChatResponse chat(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        List<ToolExecutionResultMessage> results = messages.stream()
            .filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast).toList();

        AiMessage reply;
        if (results.isEmpty()) {
            reply = call("getBreakSummary", "{}");
        } else if (results.stream().anyMatch(r -> r.text().toLowerCase(Locale.ROOT).contains("simulate agent loop"))) {
            reply = call("searchEvidence", "{\"query\":\"" + SEARCH_QUERY + "\"}");
        } else if (results.stream().noneMatch(r -> "searchEvidence".equals(r.toolName()))) {
            reply = call("searchEvidence", "{\"query\":\"" + SEARCH_QUERY + "\"}");
        } else {
            String evidence = results.stream().filter(r -> "searchEvidence".equals(r.toolName()))
                .map(ToolExecutionResultMessage::text).reduce("", (a, b) -> a + "\n" + b);
            reply = AiMessage.from(MockLlmChatModel.answer(MockLlmChatModel.evidenceBlocks(evidence)));
        }

        int promptTokens = Math.max(1, messages.stream().mapToInt(m -> String.valueOf(m).length()).sum() / 4);
        int completionTokens = Math.max(1, String.valueOf(reply).length() / 4);
        return ChatResponse.builder()
            .aiMessage(reply)
            .modelName(MODEL_NAME)
            .tokenUsage(new TokenUsage(promptTokens, completionTokens))
            .finishReason(reply.hasToolExecutionRequests() ? FinishReason.TOOL_EXECUTION : FinishReason.STOP)
            .build();
    }

    private AiMessage call(String tool, String arguments) {
        return AiMessage.from(ToolExecutionRequest.builder()
            .id("call-" + callIds.incrementAndGet()).name(tool).arguments(arguments).build());
    }
}
