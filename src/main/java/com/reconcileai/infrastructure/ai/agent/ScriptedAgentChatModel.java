package com.reconcileai.infrastructure.ai.agent;

import com.reconcileai.infrastructure.ai.llm.MockLlmChatModel;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for a tool-calling model, used under the mock-llm profile and in tests. It reads the "ROLE:"
 * line of the system prompt and plays that part; with no role line it is the agent-mode investigator.
 *
 * <pre>
 *  investigator / evidence analyst  getBreakSummary -> searchEvidence -> answer with the MockLlmChatModel keyword rules
 *  risk checker                     findSimilarResolvedBreaks(draft cause) -> flags from the facts and the tool result
 *  reviewer                         for each cited chunk: supported, quote = the chunk's first 80 characters (verbatim)
 *  supervisor (planned variant)     analyst -> risk checker -> reviewer -> DONE
 * </pre>
 *
 * <h2>Fault markers (in evidence text or the analyst note)</h2>
 * <pre>
 *  "simulate agent loop"            the investigator keeps calling search forever (budget tests)
 *  "simulate reviewer challenge"    the reviewer marks that chunk as not supported
 *  "simulate forged quote"          the reviewer says "supported" with a quote that is NOT in the chunk
 *                                   (code must turn this into a CHALLENGE)
 *  "simulate supervisor skips review"  the supervisor answers DONE without calling the reviewer
 *                                   (code must run the reviewer anyway)
 * </pre>
 */
public class ScriptedAgentChatModel implements ChatModel {

    public static final String MODEL_NAME = "mock-agent";
    public static final String FORGED_QUOTE = "the counterparty confirmed in writing that this variance is expected";
    private static final String SEARCH_QUERY = "fee timing settlement value date exchange rate duplicate missing partial payment";
    private static final Pattern CITED = Pattern.compile("<<<CITED chunk_id=([^>]+)>>>\\n(.*?)\\n<<<END_CITED>>>", Pattern.DOTALL);
    private static final Pattern DRAFT_CAUSE = Pattern.compile("DRAFT_ROOT_CAUSE: (\\w+)");

    private final AtomicInteger callIds = new AtomicInteger();

    @Override
    public ChatResponse chat(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        String system = messages.stream().filter(SystemMessage.class::isInstance).map(m -> ((SystemMessage) m).text())
            .findFirst().orElse("");
        List<ToolExecutionResultMessage> results = messages.stream()
            .filter(ToolExecutionResultMessage.class::isInstance).map(ToolExecutionResultMessage.class::cast).toList();
        String user = messages.stream().filter(UserMessage.class::isInstance).map(m -> ((UserMessage) m).singleText())
            .reduce("", (a, b) -> a + "\n" + b);

        AiMessage reply;
        if (system.startsWith(SupervisedPrompts.RISK_ROLE)) {
            reply = riskChecker(user, results);
        } else if (system.startsWith(SupervisedPrompts.REVIEWER_ROLE)) {
            reply = AiMessage.from(reviewer(user));
        } else if (system.startsWith(SupervisedPrompts.SUPERVISOR_ROLE)) {
            reply = supervisor(user, results);
        } else {
            reply = investigator(results);
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

    private AiMessage investigator(List<ToolExecutionResultMessage> results) {
        if (results.isEmpty()) {
            return call("getBreakSummary", "{}");
        }
        if (results.stream().anyMatch(r -> r.text().toLowerCase(Locale.ROOT).contains("simulate agent loop"))) {
            return call("searchEvidence", "{\"query\":\"" + SEARCH_QUERY + "\"}");
        }
        if (results.stream().noneMatch(r -> "searchEvidence".equals(r.toolName()))) {
            return call("searchEvidence", "{\"query\":\"" + SEARCH_QUERY + "\"}");
        }
        String evidence = results.stream().filter(r -> "searchEvidence".equals(r.toolName()))
            .map(ToolExecutionResultMessage::text).reduce("", (a, b) -> a + "\n" + b);
        return AiMessage.from(MockLlmChatModel.answer(MockLlmChatModel.evidenceBlocks(evidence)));
    }

    private AiMessage riskChecker(String user, List<ToolExecutionResultMessage> results) {
        Matcher cause = DRAFT_CAUSE.matcher(user);
        String rootCause = cause.find() ? cause.group(1) : "UNKNOWN";
        if (results.isEmpty() && !"UNKNOWN".equals(rootCause)) {
            return call("findSimilarResolvedBreaks", "{\"rootCause\":\"" + rootCause + "\"}");
        }
        List<String> flags = new ArrayList<>();
        if (user.contains("variance_at_or_above_l2_threshold: true")) {
            flags.add("Material variance: needs second-level sign-off");
        }
        Matcher currency = Pattern.compile("currency: (\\w+)\\npolicy_currency: (\\w+)").matcher(user);
        if (currency.find() && !currency.group(1).equals(currency.group(2))) {
            flags.add("Non-policy currency " + currency.group(1) + ": check the FX treatment");
        }
        if (results.stream().anyMatch(r -> r.text().contains("rootCause="))) {
            flags.add("Earlier approved breaks share this root cause; compare the actions taken");
        }
        String json = flags.stream().map(f -> "\"" + f + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return AiMessage.from("{\"flags\":[" + json + "]}");
    }

    private static String reviewer(String user) {
        List<String> claims = new ArrayList<>();
        Matcher m = CITED.matcher(user);
        while (m.find()) {
            String id = m.group(1);
            String text = m.group(2);
            String lower = text.toLowerCase(Locale.ROOT);
            boolean supported = !lower.contains("simulate reviewer challenge") && !text.startsWith("(this chunk id was not");
            String quote = !supported ? "" : lower.contains("simulate forged quote") ? FORGED_QUOTE : firstSentence(text);
            claims.add("{\"chunkId\":\"" + id + "\",\"supported\":" + supported + ",\"quote\":\"" + escape(quote) + "\"}");
        }
        return "{\"claims\":[" + String.join(",", claims) + "]}";
    }

    private AiMessage supervisor(String user, List<ToolExecutionResultMessage> results) {
        boolean skipReview = user.toLowerCase(Locale.ROOT).contains("simulate supervisor skips review");
        if (results.stream().noneMatch(r -> "runEvidenceAnalyst".equals(r.toolName()))) {
            return call("runEvidenceAnalyst", "{}");
        }
        if (results.stream().noneMatch(r -> "runRiskChecker".equals(r.toolName()))) {
            return call("runRiskChecker", "{}");
        }
        if (!skipReview && results.stream().noneMatch(r -> "runReviewer".equals(r.toolName()))) {
            return call("runReviewer", "{}");
        }
        return AiMessage.from("DONE");
    }

    /** The first 80 characters of the chunk: always a verbatim piece of it. */
    private static String firstSentence(String text) {
        String t = text.strip();
        return t.substring(0, Math.min(t.length(), 80)).strip();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    private AiMessage call(String tool, String arguments) {
        return AiMessage.from(ToolExecutionRequest.builder()
            .id("call-" + callIds.incrementAndGet()).name(tool).arguments(arguments).build());
    }
}
