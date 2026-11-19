// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.messaging;

import com.reconcileai.infrastructure.messaging.OutboxRelay;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.restassured.RestAssured;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real Kafka: transactional outbox relay and event-driven break intake (with idempotency and dead-lettering). */
class KafkaOutboxIT extends AbstractPostgresIT {

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("reconcile.kafka.enabled", () -> "true");
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    int port;

        final JdbcTemplate jdbc = adminJdbc();

    @Autowired
    KafkaTemplate<String, String> template;

    @Autowired
    OutboxRelay relay;

    @BeforeEach
    void setPort() {
        RestAssured.port = port;
    }

    private Consumer<String, String> consumer(String group, String topic) {
        Consumer<String, String> c = new DefaultKafkaConsumerFactory<String, String>(Map.<String, Object>of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
            new StringDeserializer(), new StringDeserializer()).createConsumer();
        c.subscribe(List.of(topic));
        return c;
    }

    private long breaks(String tenant, String reference) {
        return jdbc.queryForObject("SELECT count(*) FROM break_item WHERE tenant_id = ? AND break_reference = ?",
            Long.class, tenant, reference);
    }

    @Test
    void restCreatedBreakIsPublishedFromTheOutbox() {
        String analyst = JwtTestSupport.bearer("m-1", "TENANT_KAFKA_OUT", List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(analyst, "50500.00", "50000.00");

        // Written in the same transaction as the break, before any publishing.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?::uuid", Long.class, breakId))
            .isGreaterThanOrEqualTo(1);

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?::uuid AND published_at IS NULL", Long.class, breakId) == 0);

        try (Consumer<String, String> c = consumer("it-events", "reconcile.break.events")) {
            ConsumerRecord<String, String> found = null;
            long end = System.currentTimeMillis() + 20_000;
            while (found == null && System.currentTimeMillis() < end) {
                for (ConsumerRecord<String, String> rec : c.poll(Duration.ofMillis(500))) {
                    if (breakId.equals(rec.key())) {
                        found = rec;
                    }
                }
            }
            assertThat(found).isNotNull();
            assertThat(new String(found.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("BREAK_CREATED");
            assertThat(new String(found.headers().lastHeader("tenant-id").value(), StandardCharsets.UTF_8))
                .isEqualTo("TENANT_KAFKA_OUT");
            assertThat(found.value()).contains("variance").doesNotContain("TENANT_KAFKA_OUT");
        }
        assertThat(relay.relayOnce()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void intakeMessageCreatesABreakOnceEvenWhenRedeliveredAndMalformedGoesToDeadLetter() {
        String msg = "{\"tenantId\":\"TENANT_KAFKA_IN\",\"breakReference\":\"KAFKA-1\",\"ledgerAmount\":\"1200.50\","
            + "\"externalAmount\":\"1000.00\",\"currency\":\"USD\"}";
        template.send("reconcile.break.intake", "k1", msg);
        template.send("reconcile.break.intake", "k1", msg); // redelivery / duplicate
        template.send("reconcile.break.intake", "bad", "{\"tenantId\":\"bad tenant!\",\"breakReference\":\"X\","
            + "\"ledgerAmount\":\"1\",\"externalAmount\":\"1\",\"currency\":\"USD\"}");
        template.send("reconcile.break.intake", "junk", "not json at all");

        await().atMost(Duration.ofSeconds(60)).until(() -> breaks("TENANT_KAFKA_IN", "KAFKA-1") == 1);

        // exactly one row, source KAFKA, audited with the system actor
        assertThat(jdbc.queryForObject("SELECT source FROM break_item WHERE tenant_id = 'TENANT_KAFKA_IN'", String.class))
            .isEqualTo("KAFKA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE tenant_id = 'TENANT_KAFKA_IN' AND actor = 'system:kafka-intake'",
            Long.class)).isEqualTo(1);

        try (Consumer<String, String> c = consumer("it-dlt", "reconcile.break.intake.dlt")) {
            int dead = 0;
            long end = System.currentTimeMillis() + 40_000;
            while (dead < 2 && System.currentTimeMillis() < end) {
                dead += c.poll(Duration.ofMillis(500)).count();
            }
            assertThat(dead).isEqualTo(2);
        }
        assertThat(breaks("bad tenant!", "X")).isZero();
    }
}
