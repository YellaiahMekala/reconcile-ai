package com.reconcileai.infrastructure.ai.advisor;

import com.reconcileai.domain.LlmCallRecord;
import com.reconcileai.infrastructure.persistence.LlmCallRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;

import java.util.UUID;

/**
 * Token and latency accounting for every model call made through the investigation ChatClient.
 *
 * <p>Reads {@link Usage} straight off the {@link ChatClientResponse}. Our chain makes exactly one model call per
 * investigation and exposes no tools, so there are no nested calls this advisor could miss. (If tool calling is ever
 * added, nested calls must be accounted at the ChatModel level instead.)
 *
 * <p>Writes one {@code llm_call} row and increments Micrometer counters
 * {@code reconcile.llm.tokens{type=prompt|completion}} for dashboards and cost alerts.
 */
public class TokenUsageAdvisor implements CallAdvisor {

    public static final String TENANT_ID = "reconcile.tenantId";
    public static final String JOB_ID = "reconcile.jobId";

    private final LlmCallRepository repository;
    private final MeterRegistry meters;

    public TokenUsageAdvisor(LlmCallRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.meters = meters;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String tenantId = String.valueOf(request.context().get(TENANT_ID));
        UUID jobId = request.context().get(JOB_ID) instanceof UUID u ? u : null;
        long start = System.nanoTime();
        try {
            ChatClientResponse response = chain.nextCall(request);
            long ms = (System.nanoTime() - start) / 1_000_000;
            ChatResponseMetadata md = response.chatResponse() == null ? null : response.chatResponse().getMetadata();
            Usage usage = md == null ? null : md.getUsage();
            int prompt = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            int completion = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            String model = md == null || md.getModel() == null || md.getModel().isBlank() ? "unknown" : md.getModel();

            repository.save(new LlmCallRecord(tenantId, jobId, model, prompt, completion, ms, "OK"));
            meters.counter("reconcile.llm.tokens", "type", "prompt", "model", model).increment(prompt);
            meters.counter("reconcile.llm.tokens", "type", "completion", "model", model).increment(completion);
            return response;
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            repository.save(new LlmCallRecord(tenantId, jobId, "unknown", 0, 0, ms, "ERROR"));
            throw e;
        }
    }

    @Override
    public String getName() {
        return "TokenUsageAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200; // after the guard: blocked calls are never "used" tokens
    }
}
