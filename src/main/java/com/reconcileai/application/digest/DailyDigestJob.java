// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.application.digest;

import com.reconcileai.infrastructure.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates yesterday's digest for every tenant, once per day, on exactly one instance.
 *
 * <h2>Single run across instances: a Postgres advisory lock</h2>
 * {@code pg_try_advisory_lock} on one held connection: the first instance gets it, the others skip at once. If the
 * holder dies, Postgres releases the lock with the session. Each tenant's digest is an upsert, so even a second run
 * (manual backfill, or a race after a crash) produces the same rows, not duplicates.
 *
 * <h2>Why not ShedLock / Quartz?</h2>
 * Both are good. ShedLock needs its own table and dependency, Quartz a whole scheduler schema. With one daily job and
 * Postgres already present, an advisory lock gives the same guarantee in a few lines. With many scheduled jobs I
 * would switch to ShedLock for its lock-until/lock-at-least semantics and visibility.
 *
 * <h2>Tenant scope</h2>
 * Listing tenants is cross-tenant (explicit system scope); each digest is then built inside that tenant's scope, so
 * Row-Level Security confines it to the tenant's own rows.
 */
@Component
@ConditionalOnProperty(prefix = "reconcile.digest", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DailyDigestJob {

    private static final Logger log = LoggerFactory.getLogger(DailyDigestJob.class);
    static final long LOCK_KEY = 7_220_002L;

    private final JdbcTemplate jdbc;
    private final DailyDigestService digests;

    public DailyDigestJob(JdbcTemplate jdbc, DailyDigestService digests) {
        this.jdbc = jdbc;
        this.digests = digests;
    }

    @Scheduled(cron = "${reconcile.digest.cron:0 15 0 * * *}", zone = "UTC")
    public void daily() {
        runFor(LocalDate.now(ZoneOffset.UTC).minusDays(1));
    }

    /** @return tenants digested, or -1 if another instance holds the lock. Visible for tests and backfill. */
    public int runFor(LocalDate day) {
        return TenantScope.callAsSystem(() -> jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement lock = con.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                lock.setLong(1, LOCK_KEY);
                try (ResultSet rs = lock.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        log.info("daily digest for {} already running on another instance; skipping", day);
                        return -1;
                    }
                }
            }
            try {
                List<String> tenants = new ArrayList<>();
                try (PreparedStatement ps = con.prepareStatement("SELECT DISTINCT tenant_id FROM break_item ORDER BY 1");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tenants.add(rs.getString(1));
                    }
                }
                int done = 0;
                for (String tenant : tenants) {
                    try {
                        TenantScope.run(tenant, () -> digests.generate(tenant, day));
                        done++;
                    } catch (RuntimeException e) { // one tenant's failure must not stop the others
                        log.warn("daily digest failed for tenant {} on {}: {}", tenant, day, e.toString());
                    }
                }
                log.info("daily digest for {}: {} of {} tenants", day, done, tenants.size());
                return done;
            } finally {
                try (PreparedStatement unlock = con.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, LOCK_KEY);
                    unlock.execute();
                }
            }
        }));
    }
}
