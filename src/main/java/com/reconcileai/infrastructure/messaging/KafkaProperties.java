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
