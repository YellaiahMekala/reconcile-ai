// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

/** Topics, dead-lettering and the relay scheduler. Active only when reconcile.kafka.enabled=true. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "reconcile.kafka", name = "enabled", havingValue = "true")
public class KafkaConfig {

    @Bean
    NewTopic eventsTopic(KafkaProperties p) {
        return TopicBuilder.name(p.eventsTopic()).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic intakeTopic(KafkaProperties p) {
        return TopicBuilder.name(p.intakeTopic()).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic intakeDeadLetterTopic(KafkaProperties p) {
        return TopicBuilder.name(p.intakeDlt()).partitions(1).replicas(1).build();
    }

    /** 3 quick retries for transient failures; malformed input is never retried. Then to the dead-letter topic. */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template, KafkaProperties p) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
            (rec, ex) -> new TopicPartition(p.intakeDlt(), 0));
        DefaultErrorHandler handler = new DefaultErrorHandler(dlt, new FixedBackOff(500L, 3));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
