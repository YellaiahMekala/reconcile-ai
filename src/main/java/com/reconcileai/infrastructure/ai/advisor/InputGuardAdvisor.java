package com.reconcileai.infrastructure.ai.advisor;

import com.reconcileai.infrastructure.ai.guard.InjectionScanner;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

/**
 * First advisor in the chain: blocks the call BEFORE any tokens are spent when
 * <ul>
 *   <li>the analyst note trips the injection scanner, or</li>
 *   <li>the assembled prompt exceeds the hard prompt-size ceiling (runaway context = runaway cost).</li>
 * </ul>
 * Uses the current Spring AI advisor API: {@link CallAdvisor#adviseCall(ChatClientRequest, CallAdvisorChain)}.
 * (Older tutorials show CallAroundAdvisor/AdvisedRequest, which no longer exist in 1.x.)
 */
public class InputGuardAdvisor implements CallAdvisor {

    /** Advisor context key carrying the raw analyst note (so we scan exactly what the user typed). */
    public static final String ANALYST_NOTE = "reconcile.analystNote";

    private final InjectionScanner scanner;
    private final int maxPromptChars;

    public InputGuardAdvisor(InjectionScanner scanner, int maxPromptChars) {
        this.scanner = scanner;
        this.maxPromptChars = maxPromptChars;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Object note = request.context().get(ANALYST_NOTE);
        if (note instanceof String s && scanner.isSuspect(s)) {
            throw new PromptBlockedException("ANALYST_NOTE_INJECTION_SUSPECT");
        }
        int size = request.prompt().getContents().length();
        if (size > maxPromptChars) {
            throw new PromptBlockedException("PROMPT_TOO_LARGE");
        }
        return chain.nextCall(request);
    }

    @Override
    public String getName() {
        return "InputGuardAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    /** Thrown when the guard refuses to call the model. The job fails with this reason; nothing is billed. */
    public static class PromptBlockedException extends RuntimeException {
        public PromptBlockedException(String reason) {
            super(reason);
        }
    }
}
