package com.reconcileai.infrastructure.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox and publishes to Kafka. At-least-once: a row is marked published only after the broker acks it,
 * so a crash between ack and commit re-sends it; consumers dedupe on the {@code event-id} header.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} lets several instances relay concurrently without double-picking a row. The key is
 * the aggregate id, so all events of one break stay ordered within a partition. A failed send stops the batch (to keep
 * per-key order), counts an attempt, and is retried on the next tick.
 */
@Component
@ConditionalOnProperty(prefix = "reconcile.kafka", name = "enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaProperties props;
    private final MeterRegistry meters;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                       KafkaProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.props = props;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${reconcile.kafka.relay-interval-ms:1000}")
    public void tick() {
        relayOnce();
    }

    /** Publishes one batch; returns how many events were sent. Visible for tests. */
    public int relayOnce() {
        Integer sent = tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, tenant_id, aggregate_id, event_type, payload FROM outbox_event"
                    + " WHERE published_at IS NULL ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
                props.relayBatchSize());
            int ok = 0;
            for (Map<String, Object> r : rows) {
                UUID id = (UUID) r.get("id");
                ProducerRecord<String, String> rec = new ProducerRecord<>(props.eventsTopic(),
                    r.get("aggregate_id").toString(), (String) r.get("payload"));
                rec.headers().add("event-id", id.toString().getBytes(StandardCharsets.UTF_8));
                rec.headers().add("event-type", ((String) r.get("event_type")).getBytes(StandardCharsets.UTF_8));
                rec.headers().add("tenant-id", ((String) r.get("tenant_id")).getBytes(StandardCharsets.UTF_8));
                try {
                    kafka.send(rec).get(10, TimeUnit.SECONDS);
                    jdbc.update("UPDATE outbox_event SET published_at = ?, attempts = attempts + 1 WHERE id = ?",
                        Timestamp.from(Instant.now()), id);
                    ok++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("outbox publish failed for {}: {}", id, e.toString());
                    jdbc.update("UPDATE outbox_event SET attempts = attempts + 1 WHERE id = ?", id);
                    meters.counter("reconcile.outbox.failures").increment();
                    break; // keep per-key ordering: do not skip ahead
                }
            }
            return ok;
        });
        int n = sent == null ? 0 : sent;
        if (n > 0) {
            meters.counter("reconcile.outbox.published").increment(n);
        }
        return n;
    }
}
