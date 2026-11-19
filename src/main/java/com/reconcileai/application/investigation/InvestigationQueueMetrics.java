// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.investigation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Investigation back-log as gauges, the signal to scale on. Investigations are I/O-bound model calls, so CPU stays
 * low while the queue fills and a CPU autoscaler never reacts; {@code active + queued} per pod is what KEDA reads
 * ({@code k8s/overlays/keda}).
 * <ul>
 *   <li>{@code reconcile.investigations.executor.active}: jobs running now</li>
 *   <li>{@code reconcile.investigations.executor.queued}: jobs accepted and waiting for a thread</li>
 *   <li>{@code reconcile.investigations.executor.capacity}: queue slots left before POST answers 503</li>
 * </ul>
 */
@Component
public class InvestigationQueueMetrics implements MeterBinder {

    private final ThreadPoolTaskExecutor executor;

    public InvestigationQueueMetrics(@Qualifier("investigationExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("reconcile.investigations.executor.active", executor, ThreadPoolTaskExecutor::getActiveCount)
            .description("Investigations running on this instance").register(registry);
        Gauge.builder("reconcile.investigations.executor.queued", executor, e -> e.getQueueSize())
            .description("Investigations waiting for a thread on this instance").register(registry);
        Gauge.builder("reconcile.investigations.executor.capacity", executor,
                e -> e.getThreadPoolExecutor().getQueue().remainingCapacity())
            .description("Queue slots left before new investigations are refused with 503").register(registry);
    }
}
