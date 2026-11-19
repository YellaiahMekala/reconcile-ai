// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** reconcile.kafka.* — everything is off unless {@code enabled}; the REST path never depends on Kafka. */
@ConfigurationProperties("reconcile.kafka")
public record KafkaProperties(
    boolean enabled,
    String eventsTopic,
    String intakeTopic,
    int relayBatchSize,
    long relayIntervalMs
) {
    public KafkaProperties {
        if (relayBatchSize < 1 || relayBatchSize > 500) {
            throw new IllegalArgumentException("reconcile.kafka.relay-batch-size must be within [1, 500]");
        }
    }

    public String intakeDlt() {
        return intakeTopic + ".dlt";
    }
}
