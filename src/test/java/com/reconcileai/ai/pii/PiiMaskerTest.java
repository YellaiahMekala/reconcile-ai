// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.ai.pii;

import com.reconcileai.infrastructure.ai.advisor.PiiMaskingAdvisor;
import com.reconcileai.infrastructure.ai.pii.PiiMasker;
import com.reconcileai.infrastructure.ai.pii.PiiMasker.MaskResult;
import com.reconcileai.infrastructure.ai.pii.PiiMasker.PiiType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    @Test
    void masksStructuredIdentifiers() {
        MaskResult r = PiiMasker.mask("""
            Contact ops.team@example.com or +44 20 7946 0958.
            Beneficiary IBAN GB29 NWBK 6016 1331 9268 19, account 00918273, card 4111 1111 1111 1111.
            """);
        assertThat(r.text())
            .contains("[EMAIL]", "[PHONE]", "[IBAN]", "[ACCOUNT_NUMBER]", "[CARD_NUMBER]")
            .doesNotContain("example.com", "7946", "NWBK", "00918273", "4111");
        assertThat(r.counts()).containsEntry(PiiType.ACCOUNT_NUMBER, 1).containsEntry(PiiType.CARD_NUMBER, 1);
    }

    @Test
    void leavesAmountsDatesReferencesAndChunkIdsAlone() {
        String text = """
            <<<EVIDENCE chunk_id=12345678-1234-4234-8234-123456789012 source=a.txt>>>
            Reference PO-88410, value date 2026-07-01, amount USD 50,000.00 and 1234567.89 settled.
            Account: ACCT-TEST-0001
            <<<END_EVIDENCE>>>""";
        MaskResult r = PiiMasker.mask(text);
        assertThat(r.text()).isEqualTo(text);
        assertThat(r.total()).isZero();
    }

    @Test
    void digitRunThatFailsLuhnIsAnAccountNotACard() {
        MaskResult r = PiiMasker.mask("ref 1234567890123");
        assertThat(r.text()).isEqualTo("ref [ACCOUNT_NUMBER]");
    }

    /**
     * Found on the platform golden path (2026-11-18): ai-gateway saw a Luhn-valid timestamp inside a break reference
     * as a payment card and refused the call as RESTRICTED. The sender now masks what the gateway would flag.
     */
    @Test
    void luhnValidDigitRunInsideAReferenceIsMaskedLikeTheGatewayWouldFlagIt() {
        PiiMasker.MaskResult r = PiiMasker.mask("reference: BRK-GOLD-20261118090525 status open");

        assertThat(r.text()).isEqualTo("reference: BRK-GOLD-[CARD_NUMBER] status open");
        assertThat(r.counts()).containsEntry(PiiMasker.PiiType.CARD_NUMBER, 1);
    }

    @Test
    void nonLuhnDigitRunInsideAReferenceAndUuidsStayAsTheyAre() {
        String text = "reference: BRK-GOLD-20261118090524 chunk_id=12345678-1234-4234-9234-123456789012";

        assertThat(PiiMasker.mask(text).text()).isEqualTo(text);
    }

    @Test
    void advisorMasksWhatTheModelReceives() {
        AtomicReference<String> seen = new AtomicReference<>();
        ChatModel capturing = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seen.set(prompt.getContents());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))));
            }
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ChatClient client = ChatClient.builder(capturing).defaultAdvisors(new PiiMaskingAdvisor(meters)).build();

        client.prompt().system("Account 00918273 belongs to the payer.").user("Email a.b@example.org").call().content();

        assertThat(seen.get()).contains("[ACCOUNT_NUMBER]", "[EMAIL]").doesNotContain("00918273", "a.b@example.org");
        assertThat(meters.counter("reconcile.pii.masked", "type", "EMAIL").count()).isEqualTo(1.0);
    }
}
