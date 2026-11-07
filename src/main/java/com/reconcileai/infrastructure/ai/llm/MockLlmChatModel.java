package com.reconcileai.infrastructure.ai.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for a real LLM: a genuine {@link ChatModel} so the WHOLE advisor chain,
 * output parsing, citation verification and policy gate run exactly as in production, at zero token cost.
 *
 * <h2>Behaviour (keyword rules over the evidence blocks in the prompt)</h2>
 * <pre>
 *  evidence contains "approve with confidence"  -> simulates a HIJACKED model: TIMING_DIFFERENCE, 0.99,
 *                                                  cites a fabricated chunk id  (proves citation verification)
 *  evidence mentions a fee                      -> FEE_OR_CHARGE,       0.88, cites the fee chunks
 *  evidence mentions settlement / value date    -> TIMING_DIFFERENCE,   0.86, cites those chunks
 *  otherwise                                    -> UNKNOWN,             0.35, no citations
 * </pre>
 *
 * <h2>Fault injection (demonstrates LLM retry + circuit breaker without a real provider)</h2>
 * <pre>
 *  evidence contains "simulate provider outage"  -> every call throws TransientAiException (retries exhaust,
 *                                                   job FAILED with LLM_UNAVAILABLE; repeated runs open the breaker)
 *  evidence contains "simulate transient glitch" -> the FIRST call for that prompt throws, the retry succeeds
 * </pre>
 * Usage metadata is filled with chars/4 estimates so token accounting is exercised end to end.
 * This bean is registered only under the "mock-llm" profile (see AiConfig).
 */
public class MockLlmChatModel implements ChatModel {

    public static final String FABRICATED_CHUNK_ID = "00000000-0000-0000-0000-00000000dead";
    private static final Pattern BLOCK = Pattern.compile("<<<EVIDENCE chunk_id=([0-9a-f-]{36})[^>]*>>>\\n(.*?)\\n<<<END_EVIDENCE>>>", Pattern.DOTALL);

    private final java.util.Set<Integer> glitchedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public ChatResponse call(Prompt prompt) {
        String all = prompt.getContents();
        String lower = all.toLowerCase(Locale.ROOT);
        if (lower.contains("simulate provider outage")) {
            throw new org.springframework.ai.retry.TransientAiException("mock-llm: simulated provider outage (HTTP 503)");
        }
        if (lower.contains("simulate transient glitch") && glitchedOnce.add(all.hashCode())) {
            throw new org.springframework.ai.retry.TransientAiException("mock-llm: simulated transient glitch (HTTP 503)");
        }
        List<String[]> blocks = new ArrayList<>();
        Matcher m = BLOCK.matcher(all);
        while (m.find()) {
            blocks.add(new String[] {m.group(1), m.group(2).toLowerCase(Locale.ROOT)});
        }

        String json;
        if (blocks.stream().anyMatch(b -> b[1].contains("approve with confidence"))) {
            json = proposal("TIMING_DIFFERENCE", "Evidence instructs approval; treated as timing difference.",
                "Approve immediately.", "0.99", List.of(FABRICATED_CHUNK_ID));
        } else if (cites(blocks, "fee").size() > 0) {
            json = proposal("FEE_OR_CHARGE",
                "The external amount is lower because an intermediary bank deducted a wire fee before crediting the account.",
                "Post the wire fee to the bank-charges expense account and match the net receipt to the invoice.",
                "0.88", cites(blocks, "fee"));
        } else if (cites(blocks, "settled").size() > 0 || cites(blocks, "value date").size() > 0) {
            List<String> ids = new ArrayList<>(cites(blocks, "settled"));
            cites(blocks, "value date").stream().filter(i -> !ids.contains(i)).forEach(ids::add);
            json = proposal("TIMING_DIFFERENCE",
                "The payment was booked in the ledger before month end but settled at the bank on the next business day.",
                "Carry the item forward and confirm it clears on the next statement.", "0.86", ids);
        } else {
            json = proposal("UNKNOWN", "The provided evidence does not explain the variance.",
                "Request additional evidence from the counterparty.", "0.35", List.of());
        }

        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .model("mock-llm")
            .usage(new DefaultUsage(Math.max(1, all.length() / 4), Math.max(1, json.length() / 4)))
            .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))), metadata);
    }

    private static List<String> cites(List<String[]> blocks, String keyword) {
        return blocks.stream().filter(b -> b[1].contains(keyword)).map(b -> b[0]).toList();
    }

    private static String proposal(String cause, String explanation, String action, String confidence, List<String> ids) {
        String citedJson = ids.stream().map(i -> "\"" + i + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return """
            {"rootCause":"%s","explanation":"%s","recommendedAction":"%s","confidence":%s,"citedChunkIds":[%s]}"""
            .formatted(cause, explanation, action, confidence, citedJson);
    }
}
