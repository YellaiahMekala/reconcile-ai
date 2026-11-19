// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.infrastructure.web;

import com.reconcileai.infrastructure.persistence.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drops expired {@code Idempotency-Key} rows (TTL 24h by default), same scheduler pattern as the recovery sweep. */
@Component
public class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    private final IdempotencyStore store;

    public IdempotencyPurgeJob(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${reconcile.idempotency.purge-interval-ms:3600000}",
               initialDelayString = "${reconcile.idempotency.purge-interval-ms:3600000}")
    public void scheduledPurge() {
        purge();
    }

    /** @return rows deleted (visible for tests) */
    public int purge() {
        int deleted = store.purgeExpired();
        if (deleted > 0) {
            log.info("purged {} expired idempotency keys", deleted);
        }
        return deleted;
    }
}
