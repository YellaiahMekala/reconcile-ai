package com.reconcileai.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcileai.application.BreakIngestionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Event-driven break ingestion. A malformed message throws {@link IllegalArgumentException}, which the error handler in
 * {@link KafkaConfig} does not retry and publishes to {@code <intake>.dlt}. Transient failures (database down) are
 * retried with backoff, then dead-lettered.
 */
@Component
@ConditionalOnProperty(prefix = "reconcile.kafka", name = "enabled", havingValue = "true")
public class BreakIntakeListener {

    private static final Logger log = LoggerFactory.getLogger(BreakIntakeListener.class);

    private final BreakIngestionService ingestion;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public BreakIntakeListener(BreakIngestionService ingestion, ObjectMapper json, MeterRegistry meters) {
        this.ingestion = ingestion;
        this.json = json;
        this.meters = meters;
    }

    @KafkaListener(topics = "${reconcile.kafka.intake-topic}", groupId = "reconcile-ai-intake")
    public void onMessage(String value) {
        BreakIntakeMessage m;
        try {
            m = json.readValue(value, BreakIntakeMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unparseable intake message", e);
        }
        BreakIngestionService.Outcome outcome = ingestion.ingest(m.tenantId(), m.breakReference(), m.ledgerAmount(),
            m.externalAmount(), m.currency());
        meters.counter("reconcile.intake", "outcome", outcome.name()).increment();
        log.debug("intake {} -> {}", m.breakReference(), outcome);
    }
}
