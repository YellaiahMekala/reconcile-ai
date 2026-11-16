package com.reconcileai.infrastructure.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gateway-supplied codes must fit the column and be plain codes, or the row (and its invocation id) would be lost. */
class JpaGatewayCallLogTest {

    @Test
    void keepsPlainCodesAndReplacesAnythingElse() {
        assertThat(JpaGatewayCallLog.safe("UNKNOWN_OUTCOME", 48)).isEqualTo("UNKNOWN_OUTCOME");
        assertThat(JpaGatewayCallLog.safe(null, 48)).isNull();
        assertThat(JpaGatewayCallLog.safe("A".repeat(49), 48)).isEqualTo("UNMAPPED");
        assertThat(JpaGatewayCallLog.safe("bad code; DROP", 48)).isEqualTo("UNMAPPED");
        assertThat(JpaGatewayCallLog.safe("OK", 32)).isEqualTo("OK");
    }
}
