// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.agent;

import com.reconcileai.domain.LlmCallRecord;
import com.reconcileai.infrastructure.ai.resilience.LlmResilience;
import com.reconcileai.infrastructure.persistence.LlmCallRepository;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import java.time.Duration;
import java.util.Set;

/**
 * Wraps the agent's chat model for one run. Each model call counts as a turn (and fails the run past the limit), goes
 * through the same retry and circuit breaker as the single-call mode, and is written to llm_call and the token metrics.
 * LangChain4j exceptions are translated into the Spring AI transient / non-transient types that
 * {@link LlmResilience} already knows how to treat.
 */
class AccountingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final AgentRunContext ctx;
    private final LlmCallRepository llmCalls;
    private final MeterRegistry meters;
    private final LlmResilience resilience;
    private final double promptUsdPerMillion;
    private final double completionUsdPerMillion;

    AccountingChatModel(ChatModel delegate, AgentRunContext ctx, LlmCallRepository llmCalls, MeterRegistry meters,
                        LlmResilience resilience, double promptUsdPerMillion, double completionUsdPerMillion) {
        this.delegate = delegate;
        this.ctx = ctx;
        this.llmCalls = llmCalls;
        this.meters = meters;
        this.resilience = resilience;
        this.promptUsdPerMillion = promptUsdPerMillion;
        this.completionUsdPerMillion = completionUsdPerMillion;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ctx.startModelTurn();
        long start = System.nanoTime();
        try {
            ChatResponse response = resilience.call(() -> translated(request));
            record(response, (System.nanoTime() - start) / 1_000_000);
            return response;
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            llmCalls.save(new LlmCallRecord(ctx.tenantId(), ctx.jobId(), "unknown", 0, 0, ms, "ERROR"));
            meters.counter("reconcile.llm.errors", "exception", e.getClass().getSimpleName()).increment();
            throw e;
        }
    }

    private ChatResponse translated(ChatRequest request) {
        try {
            return delegate.chat(request);
        } catch (RateLimitException | InternalServerException | TimeoutException e) {
            throw new TransientAiException(e.getMessage(), e);
        } catch (AuthenticationException | InvalidRequestException | ModelNotFoundException e) {
            throw new NonTransientAiException(e.getMessage(), e);
        }
    }

    private void record(ChatResponse response, long ms) {
        TokenUsage usage = response.tokenUsage();
        int prompt = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int completion = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        String model = response.modelName() == null || response.modelName().isBlank() ? "unknown" : response.modelName();

        llmCalls.save(new LlmCallRecord(ctx.tenantId(), ctx.jobId(), model, prompt, completion, ms, "OK"));
        meters.counter("reconcile.llm.tokens", "type", "prompt", "model", model).increment(prompt);
        meters.counter("reconcile.llm.tokens", "type", "completion", "model", model).increment(completion);
        meters.timer("reconcile.llm.latency", "model", model, "outcome", "OK").record(Duration.ofMillis(ms));
        meters.counter("reconcile.llm.cost.usd", "model", model)
            .increment((prompt * promptUsdPerMillion + completion * completionUsdPerMillion) / 1_000_000d);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
