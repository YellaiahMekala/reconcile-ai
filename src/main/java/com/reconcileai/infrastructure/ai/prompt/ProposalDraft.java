package com.reconcileai.infrastructure.ai.prompt;

import java.math.BigDecimal;
import java.util.List;

/**
 * The ONLY shape the model is allowed to return. BeanOutputConverter derives a JSON schema from this record and
 * appends it to the system prompt; the response is parsed back into it. Anything that does not parse fails the job
 * (retryable) instead of being guessed at.
 *
 * <p>Values are UNTRUSTED until {@link InvestigationPromptBuilder#normalize} clamps and coerces them.
 */
public record ProposalDraft(
    String rootCause,
    String explanation,
    String recommendedAction,
    BigDecimal confidence,
    List<String> citedChunkIds
) {}
