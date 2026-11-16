package com.reconcileai.infrastructure.ai;

import com.reconcileai.application.gateway.GatewayChatPort;
import com.reconcileai.application.gateway.IdempotencyKeys;
import com.reconcileai.infrastructure.ai.advisor.InputGuardAdvisor;
import com.reconcileai.infrastructure.ai.advisor.TokenUsageAdvisor;
import com.reconcileai.infrastructure.ai.pii.PiiMasker;
import com.reconcileai.infrastructure.ai.pii.PiiMasker.MaskResult;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder.BuiltPrompt;
import com.reconcileai.infrastructure.ai.prompt.ProposalDraft;
import com.reconcileai.infrastructure.ai.resilience.LlmResilience;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Makes the single, NON-STREAMING model call for an investigation and parses the structured result.
 *
 * <h2>Why not streaming here?</h2>
 * The proposal is decision-critical: it must be parsed, schema-checked and citation-verified BEFORE anyone sees it.
 * Streamed tokens would reach the client before validation could run. Progress is streamed instead.
 *
 * <h2>Why not ChatClient.entity(Class)?</h2>
 * {@code .entity()} returns only the object; we also need the ChatResponse metadata (usage) that TokenUsageAdvisor
 * records, so we take {@code chatClientResponse()} and run BeanOutputConverter ourselves.
 *
 * <h2>Resilience</h2>
 * The call runs under {@link LlmResilience}: transient failures are retried with backoff, and a circuit breaker
 * fails fast (LLM_CIRCUIT_OPEN, zero spend) while the provider is down.
 */
@Component
public class InvestigationAiClient {

    /** Workflow step name in the idempotency key; a re-claimed job reuses it (see {@link IdempotencyKeys}). */
    static final String STEP = "investigate";
    /** Deadline for the gateway call, including its one permitted transport retry. */
    static final Duration GATEWAY_DEADLINE = Duration.ofSeconds(120);

    private final ChatClient chatClient;
    private final int maxOutputTokens;
    private final LlmResilience resilience;
    private final ObjectProvider<GatewayChatPort> gateway;
    private final ObjectProvider<MeterRegistry> meters;
    private final BeanOutputConverter<ProposalDraft> converter = new BeanOutputConverter<>(ProposalDraft.class);

    public InvestigationAiClient(ChatClient investigationChatClient,
                                 @Value("${reconcile.investigation.max-output-tokens}") int maxOutputTokens,
                                 LlmResilience resilience,
                                 ObjectProvider<GatewayChatPort> gateway,
                                 ObjectProvider<MeterRegistry> meters) {
        this.chatClient = investigationChatClient;
        this.resilience = resilience;
        this.maxOutputTokens = maxOutputTokens;
        this.gateway = gateway;
        this.meters = meters;
    }

    public ProposalDraft investigate(BuiltPrompt prompt, String tenantId, UUID jobId, String analystNote) {
        GatewayChatPort port = gateway.getIfAvailable();
        if (port != null) {
            return viaGateway(port, prompt, tenantId, jobId);
        }
        return direct(prompt, tenantId, jobId, analystNote);
    }

    /**
     * Platform Q2.1: the gateway route. The gateway applies its own governance (risk signalling, budget, ledger),
     * so this path does not run the ChatClient advisors or {@link LlmResilience}; the one transport retry lives in
     * the adapter, under the same idempotency key. Typed handling of each gateway code is Q2.2.
     * <p>
     * Structured identifiers (IBAN, card, account, e-mail, phone) are masked on the wire exactly as the direct
     * path's {@code PiiMaskingAdvisor} does (platform Q2.5): the gateway must never receive what the model must
     * never see. The injection guard is not repeated here: the analyst note was already scanned at job start, and
     * the gateway runs its own risk detectors on the request.
     */
    private ProposalDraft viaGateway(GatewayChatPort port, BuiltPrompt prompt, String tenantId, UUID jobId) {
        MaskResult system = PiiMasker.mask(prompt.system() + "\n" + converter.getFormat());
        MaskResult user = PiiMasker.mask(prompt.user());
        countMasked(system);
        countMasked(user);
        GatewayChatPort.Request request = new GatewayChatPort.Request(
            tenantId,
            jobId,
            IdempotencyKeys.forStep(jobId, STEP, 1),
            system.text(),
            user.text(),
            maxOutputTokens,
            0.0,
            Instant.now().plus(GATEWAY_DEADLINE));
        GatewayChatPort.Reply reply = port.chat(request);
        if (reply.output() == null || reply.output().isBlank()) {
            throw new IllegalStateException("MODEL_RETURNED_NO_RESULT");
        }
        try {
            return converter.convert(reply.output());
        } catch (RuntimeException e) {
            throw new IllegalStateException("MODEL_OUTPUT_NOT_VALID_JSON", e);
        }
    }

    /** Same series as PiiMaskingAdvisor ({@code reconcile.pii.masked{type}}), so the dashboard panel covers both routes. */
    private void countMasked(MaskResult result) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry == null) {
            return;
        }
        result.counts().forEach((type, n) ->
            registry.counter("reconcile.pii.masked", "type", type.name()).increment(n));
    }

    private ProposalDraft direct(BuiltPrompt prompt, String tenantId, UUID jobId, String analystNote) {
        // Retry (transient only) inside a circuit breaker; see LlmResilience for what is and is not retried.
        ChatClientResponse response = resilience.call(() -> chatClient.prompt()
            .system(prompt.system() + "\n" + converter.getFormat())
            .user(prompt.user())
            .options(ChatOptions.builder().temperature(0.0).maxTokens(maxOutputTokens).build())
            .advisors(a -> a
                .param(TokenUsageAdvisor.TENANT_ID, tenantId)
                .param(TokenUsageAdvisor.JOB_ID, jobId)
                .param(InputGuardAdvisor.ANALYST_NOTE, analystNote == null ? "" : analystNote))
            .call()
            .chatClientResponse());

        if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
            throw new IllegalStateException("MODEL_RETURNED_NO_RESULT");
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        try {
            return converter.convert(text);
        } catch (RuntimeException e) {
            throw new IllegalStateException("MODEL_OUTPUT_NOT_VALID_JSON", e);
        }
    }
}
