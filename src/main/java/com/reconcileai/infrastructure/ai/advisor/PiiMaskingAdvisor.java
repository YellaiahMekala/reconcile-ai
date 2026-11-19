// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.ai.advisor;

import com.reconcileai.infrastructure.ai.pii.NerDetector;
import com.reconcileai.infrastructure.ai.pii.NerMasker;
import com.reconcileai.infrastructure.ai.pii.PiiMasker;
import com.reconcileai.infrastructure.ai.pii.PiiMasker.MaskResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

/**
 * Masks structured identifiers (accounts, IBANs, cards, emails, phones) in every message before the model sees it.
 *
 * <p>Order: after {@link InputGuardAdvisor} (the injection scan runs on the raw analyst note) and before
 * {@link TokenUsageAdvisor}, so what is measured is exactly what was sent. Counts per type are published as
 * {@code reconcile.pii.masked{type=...}}; masked values are never logged.
 *
 * <p>Optional second pass: when a {@link NerDetector} (Presidio sidecar) is configured, person names and locations are
 * masked too. If the sidecar is down the advisor either fails closed (the call is refused, nothing leaves the process)
 * or fails open (identifier masking only, counted in {@code reconcile.pii.ner.unavailable}), per configuration.
 */
public class PiiMaskingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PiiMaskingAdvisor.class);

    private final MeterRegistry meters;
    private final NerDetector ner;
    private final boolean failClosed;

    public PiiMaskingAdvisor(MeterRegistry meters) {
        this(meters, null, false);
    }

    public PiiMaskingAdvisor(MeterRegistry meters, NerDetector ner, boolean failClosed) {
        this.meters = meters;
        this.ner = ner;
        this.failClosed = failClosed;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Prompt prompt = request.prompt();
        int total = 0;
        List<Message> masked = new ArrayList<>();
        for (Message m : prompt.getInstructions()) {
            if (m instanceof UserMessage u) {
                MaskResult r = count(PiiMasker.mask(u.getText()));
                total += r.total();
                String text = maskNames(r.text());
                if (!text.equals(r.text())) {
                    total++;
                }
                masked.add(u.mutate().text(text).build());
            } else if (m instanceof SystemMessage s) {
                MaskResult r = count(PiiMasker.mask(s.getText()));
                total += r.total();
                String text = maskNames(r.text());
                if (!text.equals(r.text())) {
                    total++;
                }
                masked.add(new SystemMessage(text));
            } else {
                masked.add(m);
            }
        }
        if (total == 0) {
            return chain.nextCall(request);
        }
        log.debug("masked {} identifier(s) before the model call", total);
        return chain.nextCall(request.mutate().prompt(new Prompt(masked, prompt.getOptions())).build());
    }

    /** Second pass on text whose structured identifiers were already replaced. */
    private String maskNames(String text) {
        if (ner == null) {
            return text;
        }
        try {
            NerMasker.Result r = NerMasker.apply(text, ner.detect(text));
            r.counts().forEach((type, n) -> meters.counter("reconcile.pii.masked", "type", type).increment(n));
            return r.text();
        } catch (NerDetector.NerUnavailableException e) {
            meters.counter("reconcile.pii.ner.unavailable").increment();
            if (failClosed) {
                throw new IllegalStateException("PII entity recognition unavailable; refusing to send unmasked text", e);
            }
            log.warn("PII entity recognition unavailable, continuing with identifier masking only: {}", e.getMessage());
            return text;
        }
    }

    private MaskResult count(MaskResult r) {
        r.counts().forEach((type, n) -> meters.counter("reconcile.pii.masked", "type", type.name()).increment(n));
        return r;
    }

    @Override
    public String getName() {
        return "PiiMaskingAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 150; // guard (100) -> mask (150) -> usage (200)
    }
}
