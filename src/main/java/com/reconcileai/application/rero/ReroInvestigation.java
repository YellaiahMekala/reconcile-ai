package com.reconcileai.application.rero;

import com.reconcileai.application.rero.ReroClientPort.Limits;
import com.reconcileai.application.rero.ReroClientPort.ReroRun;
import com.reconcileai.application.rero.ReroClientPort.SelfCheck;
import com.reconcileai.application.rero.ReroClientPort.StartRequest;
import com.reconcileai.application.knowledge.KnowledgeHit;
import com.reconcileai.domain.InvestigationJob;
import com.reconcileai.domain.JobStage;
import com.reconcileai.domain.RootCauseCategory;
import com.reconcileai.infrastructure.ai.pii.PiiMasker;
import com.reconcileai.infrastructure.ai.prompt.InvestigationPromptBuilder.NormalizedDraft;
import com.reconcileai.infrastructure.rero.ReroProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One RERO investigation (platform Q3.5), run by InvestigationJobRunner on its pool thread for a claimed RERO job.
 * <pre>
 *  mint run token (TTL deadline + 30 s, memory only)
 *  POST /v1/recommendations (Idempotency-Key = jobId; 202 new or 200 same run)
 *  poll GET until SUCCEEDED / FAILED, or our own deadline (deadline + grace)   stage updates -> job stage + SSE
 *  FAILED / deadline / transport error  -> ReroCallException (retryable, no proposal)
 *  SUCCEEDED -> validate body; citedChunkIds must be a subset of the chunk ids WE served to this job; kb: never
 *            -> Outcome (draft + selfCheck); the runner then runs the SAME citation check, policy gate and
 *               proposal path as every other mode. CHALLENGE becomes reviewerChallenged=true; PASS changes nothing.
 * </pre>
 * Deliberately has no access to approval, resolution or routing code (ReroArchitectureTest): RERO's answer is an input to
 * the policy gate, never a decision.
 */
public class ReroInvestigation {

    private static final Logger log = LoggerFactory.getLogger(ReroInvestigation.class);
    static final String RECOMMENDED_ACTION =
        "Review the RERO explanation and its cited evidence; RERO proposes no route, approval or status change.";
    private static final int MAX_EXPLANATION = 2000;
    private static final int MAX_NOTE = 2000;
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 3;

    /** Waits between polls; replaced in unit tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Progress callbacks into the runner (heartbeat, job stage, run correlation). */
    public interface Listener {
        void started(UUID runId);

        void stage(JobStage stage);
    }

    /** A validated recommendation, ready for the shared verify/route/persist path. */
    public record Outcome(UUID runId, NormalizedDraft draft, SelfCheck selfCheck, List<String> servedChunkIds,
                          List<String> gatewayInvocationIds) {
        public boolean challenged() {
            return selfCheck == SelfCheck.CHALLENGE; // PASS can never lower review: it maps to "no challenge" only
        }
    }

    private final ReroClientPort client;
    private final RunTokenIssuer tokens;
    private final ReroRunStore store;
    private final ReroProperties props;
    private final Clock clock;
    private final Sleeper sleeper;

    public ReroInvestigation(ReroClientPort client, RunTokenIssuer tokens, ReroRunStore store, ReroProperties props,
                             Clock clock, Sleeper sleeper) {
        this.client = client;
        this.tokens = tokens;
        this.store = store;
        this.props = props;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public Outcome run(InvestigationJob job, Listener listener) {
        String tenantId = job.getTenantId();
        UUID jobId = job.getId();
        String runToken;
        try {
            runToken = tokens.mint(tenantId, jobId, props.runTokenTtl());
        } catch (RuntimeException e) {
            throw new ReroCallException(ReroCallException.TOKEN_UNAVAILABLE);
        }
        Integer max = job.getToolCallsMax();
        Limits limits = new Limits(max == null ? props.maxToolCalls() : max, ReroProperties.MAX_MODEL_CALLS,
            props.deadlineSeconds());
        Instant giveUpAt = clock.instant().plusSeconds(props.deadlineSeconds()).plus(props.deadlineGrace());

        listener.stage(JobStage.CALLING_MODEL);
        ReroRun run = client.start(tenantId, new StartRequest(jobId, maskedNote(job.getAnalystNote()), limits, runToken));
        runToken = null; // not kept beyond the start request
        requireSameJob(run, jobId);
        listener.started(run.runId());

        int pollFailures = 0;
        while (!run.terminal()) {
            if (!clock.instant().isBefore(giveUpAt)) {
                throw new ReroCallException(ReroCallException.DEADLINE, run.gatewayInvocationIds());
            }
            listener.stage(jobStage(run.stage()));
            pause();
            try {
                run = client.get(tenantId, run.runId());
                requireSameJob(run, jobId);
                pollFailures = 0;
            } catch (ReroCallException e) {
                if (!ReroCallException.UNAVAILABLE.equals(e.code()) || ++pollFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                    throw e;
                }
                log.debug("RERO poll for job {} failed ({}), retrying", jobId, e.code());
            }
        }
        if (run.status() == ReroClientPort.Status.FAILED) {
            throw ReroCallException.runFailed(run.failureCode(), run.gatewayInvocationIds());
        }
        listener.stage(JobStage.VERIFYING);
        List<String> served = store.servedChunkIds(tenantId, jobId);
        NormalizedDraft draft = validate(run, served);
        return new Outcome(run.runId(), draft, run.result().selfCheck(), served, run.gatewayInvocationIds());
    }

    /** Contract checks on a SUCCEEDED body, then reconcile-ai's own citation re-check. */
    static NormalizedDraft validate(ReroRun run, List<String> servedChunkIds) {
        ReroClientPort.Result r = run.result();
        if (r == null || r.selfCheck() == null || r.explanation() == null || r.confidence() == null
                || r.rootCauseCategory() == null || r.explanation().length() > MAX_EXPLANATION
                || r.confidence().compareTo(BigDecimal.ZERO) < 0 || r.confidence().compareTo(BigDecimal.ONE) > 0) {
            throw new ReroCallException(ReroCallException.INVALID_RESPONSE, run.gatewayInvocationIds());
        }
        RootCauseCategory cause;
        try {
            cause = RootCauseCategory.valueOf(r.rootCauseCategory());
        } catch (IllegalArgumentException e) {
            throw new ReroCallException(ReroCallException.INVALID_RESPONSE, run.gatewayInvocationIds());
        }
        Set<String> served = new HashSet<>(servedChunkIds);
        for (String cited : r.citedChunkIds()) {
            // kb: procedure refs are never evidence; anything not served by our evidence tool to THIS job is invented.
            if (cited == null || cited.startsWith(KnowledgeHit.REF_PREFIX) || !served.contains(cited)) {
                throw new ReroCallException(ReroCallException.INVALID_CITATION, run.gatewayInvocationIds());
            }
        }
        return new NormalizedDraft(cause, PiiMasker.mask(r.explanation()).text(), RECOMMENDED_ACTION,
            r.confidence().setScale(3, RoundingMode.HALF_UP), r.citedChunkIds().stream().distinct().toList());
    }

    private static void requireSameJob(ReroRun run, UUID jobId) {
        if (run == null || run.runId() == null || run.status() == null || !jobId.equals(run.jobId())) {
            throw new ReroCallException(ReroCallException.INVALID_RESPONSE);
        }
    }

    private void pause() {
        try {
            sleeper.sleep(props.pollInterval().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReroCallException(ReroCallException.UNAVAILABLE);
        }
    }

    static JobStage jobStage(ReroClientPort.Stage stage) {
        if (stage == null) {
            return JobStage.CALLING_MODEL;
        }
        return switch (stage) {
            case LOAD_CONTEXT, RETRIEVE -> JobStage.RETRIEVING;
            case DRAFT, SELF_CHECK -> JobStage.CALLING_MODEL;
            case DONE -> JobStage.VERIFYING;
        };
    }

    private static String maskedNote(String note) {
        if (note == null || note.isBlank()) {
            return "";
        }
        String masked = PiiMasker.mask(note).text();
        return masked.length() > MAX_NOTE ? masked.substring(0, MAX_NOTE) : masked;
    }
}
