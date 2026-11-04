package com.reconcileai.infrastructure.ai;

import com.reconcileai.infrastructure.ai.advisor.InputGuardAdvisor;
import com.reconcileai.infrastructure.ai.advisor.TokenUsageAdvisor;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder.BuiltPrompt;
import com.reconcileai.infrastructure.ai.prompt.ProposalDraft;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 */
@Component
public class InvestigationAiClient {

    private final ChatClient chatClient;
    private final int maxOutputTokens;
    private final BeanOutputConverter<ProposalDraft> converter = new BeanOutputConverter<>(ProposalDraft.class);

    public InvestigationAiClient(ChatClient investigationChatClient,
                                 @Value("${reconcile.investigation.max-output-tokens}") int maxOutputTokens) {
        this.chatClient = investigationChatClient;
        this.maxOutputTokens = maxOutputTokens;
    }

    public ProposalDraft investigate(BuiltPrompt prompt, String tenantId, UUID jobId, String analystNote) {
        ChatClientResponse response = chatClient.prompt()
            .system(prompt.system() + "\n" + converter.getFormat())
            .user(prompt.user())
            .options(ChatOptions.builder().temperature(0.0).maxTokens(maxOutputTokens).build())
            .advisors(a -> a
                .param(TokenUsageAdvisor.TENANT_ID, tenantId)
                .param(TokenUsageAdvisor.JOB_ID, jobId)
                .param(InputGuardAdvisor.ANALYST_NOTE, analystNote == null ? "" : analystNote))
            .call()
            .chatClientResponse();

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
