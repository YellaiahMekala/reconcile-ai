// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.investigation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The back-log gauges KEDA scales on follow the executor: running, waiting, and slots left. */
class InvestigationQueueMetricsTest {

    @Test
    void gaugesReportActiveQueuedAndRemainingCapacity() throws Exception {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(5);
        ex.initialize();
        CountDownLatch release = new CountDownLatch(1);
        try {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new InvestigationQueueMetrics(ex).bindTo(registry);
            assertThat(gauge(registry, "capacity")).isEqualTo(5.0);

            for (int i = 0; i < 3; i++) {
                ex.execute(() -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            await().atMost(5, TimeUnit.SECONDS).until(() -> gauge(registry, "active") == 1.0);
            assertThat(gauge(registry, "queued")).isEqualTo(2.0);
            assertThat(gauge(registry, "capacity")).isEqualTo(3.0);

            release.countDown();
            await().atMost(5, TimeUnit.SECONDS)
                .until(() -> gauge(registry, "active") == 0.0 && gauge(registry, "queued") == 0.0);
        } finally {
            release.countDown();
            ex.shutdown();
        }
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get("reconcile.investigations.executor." + name).gauge().value();
    }
}
