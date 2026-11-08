package com.reconcileai.application.audit.anchor;

import com.reconcileai.infrastructure.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Periodic anchoring on exactly one instance (Postgres advisory lock, same pattern as the daily digest). A second run
 * finds nothing new and writes nothing, so a crash between cycles or a manual trigger is harmless.
 */
public class AuditAnchorJob {

    private static final Logger log = LoggerFactory.getLogger(AuditAnchorJob.class);
    static final long LOCK_KEY = 7_220_003L;

    private final JdbcTemplate jdbc;
    private final AuditAnchorService service;

    public AuditAnchorJob(JdbcTemplate jdbc, AuditAnchorService service) {
        this.jdbc = jdbc;
        this.service = service;
    }

    @Scheduled(cron = "${reconcile.audit.anchor.cron:0 */15 * * * *}", zone = "UTC")
    public void scheduled() {
        runOnce();
    }

    /** @return anchors written, or -1 when another instance holds the lock */
    public int runOnce() {
        return TenantScope.callAsSystem(() -> jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement lock = con.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                lock.setLong(1, LOCK_KEY);
                try (ResultSet rs = lock.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        log.info("audit anchoring already running on another instance; skipping");
                        return -1;
                    }
                }
            }
            try {
                return service.anchorAll();
            } finally {
                try (PreparedStatement unlock = con.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, LOCK_KEY);
                    unlock.execute();
                }
            }
        }));
    }
}
