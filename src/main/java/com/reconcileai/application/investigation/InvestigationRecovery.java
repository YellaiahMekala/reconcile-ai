package com.reconcileai.application.investigation;

import com.reconcileai.domain.InvestigationJob;
import com.reconcileai.domain.JobStatus;
import com.reconcileai.infrastructure.persistence.InvestigationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * Restart and multi-instance recovery for investigation jobs.
 *
 * <ul>
 *   <li><b>RUNNING with a stale heartbeat</b> (older than lease-seconds): the owning instance crashed or was restarted
 *       mid-call. The job is marked FAILED (INTERRUPTED) and the break goes to FAILED_RETRYABLE. It is NOT silently
 *       re-run, because the model call may already have been billed; the analyst re-triggers explicitly.</li>
 *   <li><b>QUEUED older than the lease</b>: accepted but never started (the node died with it in its in-memory queue).
 *       It is resubmitted; the atomic claim guarantees only one instance runs it.</li>
 * </ul>
 * Runs once at startup and then every sweep-interval-ms, so a surviving instance also rescues a dead peer's jobs.
 * Heartbeat leases (rather than "all RUNNING jobs at startup") keep this safe when several instances share one DB.
 */
@Component
public class InvestigationRecovery {

    private static final Logger log = LoggerFactory.getLogger(InvestigationRecovery.class);

    private final InvestigationJobRepository jobs;
    private final InvestigationJobRunner runner;
    private final InvestigationProperties props;

    public InvestigationRecovery(InvestigationJobRepository jobs, InvestigationJobRunner runner, InvestigationProperties props) {
        this.jobs = jobs;
        this.runner = runner;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        sweep();
    }

    @Scheduled(fixedDelayString = "${reconcile.investigation.sweep-interval-ms}",
               initialDelayString = "${reconcile.investigation.sweep-interval-ms}")
    public void scheduledSweep() {
        sweep();
    }

    /** @return number of jobs acted on (visible for tests) */
    public int sweep() {
        List<InvestigationJob> stale = jobs.findStale(Instant.now().minusSeconds(props.leaseSeconds()));
        for (InvestigationJob j : stale) {
            if (j.getStatus() == JobStatus.RUNNING) {
                log.warn("Recovering orphaned RUNNING job {} (owner={}, heartbeat={})", j.getId(), j.getOwnerNode(), j.getHeartbeatAt());
                runner.failJob(j.getId(), j.getTenantId(), "INTERRUPTED");
            } else {
                try {
                    log.info("Resubmitting stale QUEUED job {}", j.getId());
                    runner.submit(j.getId(), j.getTenantId());
                } catch (RejectedExecutionException full) {
                    log.warn("Executor full; stale job {} will be retried on the next sweep", j.getId());
                }
            }
        }
        return stale.size();
    }
}
