package com.reconcileai.gateway;

import com.reconcileai.application.gateway.IdempotencyKeys;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeysTest {

    @Test
    void keyHasTheAgreedFormatAndMeetsTheGatewayRule() {
        UUID jobId = UUID.fromString("0f4b1c2e-6a7d-4f2a-9b3c-1d2e3f4a5b6c");
        String key = IdempotencyKeys.forStep(jobId, "investigate", 1);
        assertThat(key).isEqualTo("recon:0f4b1c2e-6a7d-4f2a-9b3c-1d2e3f4a5b6c:investigate:1");
        assertThat(IdempotencyKeys.GATEWAY_RULE.matcher(key).matches()).isTrue();
        assertThat(key.length()).isBetween(8, 128);
    }

    @Test
    void sameInputsGiveTheSameKey() {
        UUID jobId = UUID.randomUUID();
        assertThat(IdempotencyKeys.forStep(jobId, "investigate", 1))
            .isEqualTo(IdempotencyKeys.forStep(jobId, "investigate", 1));
    }

    @Test
    void rejectsStepNamesThatWouldBreakTheRule() {
        UUID jobId = UUID.randomUUID();
        assertThatThrownBy(() -> IdempotencyKeys.forStep(jobId, "Investigate", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.forStep(jobId, "step one", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.forStep(jobId, "investigate", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.forStep(null, "investigate", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireValidAppliesTheGatewayRule() {
        assertThatThrownBy(() -> IdempotencyKeys.requireValid("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.requireValid("has space in it")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKeys.requireValid("x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThat(IdempotencyKeys.requireValid("x".repeat(128))).hasSize(128);
    }
}
