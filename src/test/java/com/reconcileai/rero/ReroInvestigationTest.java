package com.reconcileai.rero;

import com.reconcileai.application.rero.ReroCallException;
import com.reconcileai.application.rero.ReroClientPort;
import com.reconcileai.application.rero.ReroClientPort.ReroRun;
import com.reconcileai.application.rero.ReroClientPort.Result;
import com.reconcileai.application.rero.ReroClientPort.SelfCheck;
import com.reconcileai.application.rero.ReroClientPort.Stage;
import com.reconcileai.application.rero.ReroClientPort.Status;
import com.reconcileai.application.rero.ReroInvestigation;
import com.reconcileai.application.rero.ReroRunStore;
import com.reconcileai.domain.InvestigationJob;
import com.reconcileai.domain.InvestigationMode;
import com.reconcileai.domain.JobStage;
import com.reconcileai.infrastructure.rero.ReroProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests of the RERO orchestration: polling, deadline, contract validation and the one-way self-check. */
class ReroInvestigationTest {

    private static final String SERVED = UUID.randomUUID().toString();

    private final MutableClock clock = new MutableClock();
    private final ReroProperties props = new ReroProperties(true, "http://rero", null, null, null, false, null, null,
        Duration.ofMillis(100), 5, Duration.ofSeconds(1), 8, 0, null, null);

    private final InvestigationJob job = job();
    private final Deque<Object> script = new ArrayDeque<>();
    private final List<ReroClientPort.StartRequest> starts = new ArrayList<>();
    private final List<JobStage> stages = new ArrayList<>();

    private final ReroClientPort client = new ReroClientPort() {
        @Override
        public ReroRun start(String tenantId, StartRequest request) {
            starts.add(request);
            return next();
        }

        @Override
        public ReroRun get(String tenantId, UUID runId) {
            return next();
        }

        private ReroRun next() {
            Object o = script.isEmpty() ? running() : script.poll();
            if (o instanceof ReroCallException e) {
                throw e;
            }
            return (ReroRun) o;
        }
    };

    private final ReroRunStore store = new ReroRunStore() {
        @Override
        public OptionalInt tryConsumeToolCall(String t, UUID j) { return OptionalInt.empty(); }

        @Override
        public void recordServedChunks(String t, UUID j, Collection<String> ids) { }

        @Override
        public List<String> servedChunkIds(String t, UUID j) { return List.of(SERVED); }

        @Override
        public void recordToolCall(String t, UUID j, int s, String tool, int c, String o, long l) { }
    };

    private final ReroInvestigation rero = new ReroInvestigation(client, (t, j, ttl) -> "token-" + ttl.toSeconds(),
        store, props, clock, ms -> clock.advance(Duration.ofMillis(ms)));

    private final ReroInvestigation.Listener listener = new ReroInvestigation.Listener() {
        @Override
        public void started(UUID runId) { }

        @Override
        public void stage(JobStage stage) {
            stages.add(stage);
        }
    };

    @Test
    void passProducesADraftThatDoesNotChallenge() {
        script.add(running());
        script.add(succeeded(result(List.of(SERVED), SelfCheck.PASS)));
        ReroInvestigation.Outcome out = rero.run(job, listener);
        assertThat(out.challenged()).isFalse();
        assertThat(out.draft().citedChunkIds()).containsExactly(SERVED);
        assertThat(out.draft().confidence()).isEqualByComparingTo("0.900");
        assertThat(starts.get(0).runToken()).isEqualTo("token-35"); // TTL = deadline 5 s + 30 s
        assertThat(starts.get(0).limits().maxModelCalls()).isEqualTo(2);
        assertThat(starts.get(0).toString()).doesNotContain("token-");
        assertThat(stages).contains(JobStage.CALLING_MODEL, JobStage.VERIFYING);
    }

    @Test
    void challengeIsTheReviewerChallengeInput() {
        script.add(succeeded(result(List.of(SERVED), SelfCheck.CHALLENGE)));
        assertThat(rero.run(job, listener).challenged()).isTrue();
    }

    @Test
    void citationsOutsideTheServedSetOrKnowledgeRefsFail() {
        script.add(succeeded(result(List.of(UUID.randomUUID().toString()), SelfCheck.PASS)));
        assertThatThrownBy(() -> rero.run(job, listener)).hasMessage("RERO_INVALID_CITATION");
        script.add(succeeded(result(List.of("kb:" + SERVED), SelfCheck.PASS)));
        assertThatThrownBy(() -> rero.run(job, listener)).hasMessage("RERO_INVALID_CITATION");
    }

    @Test
    void contractViolationsAreInvalidResponses() {
        script.add(succeeded(new Result("NOT_A_CAUSE", "x", List.of(SERVED), new BigDecimal("0.9"), SelfCheck.PASS, "")));
        assertThatThrownBy(() -> rero.run(job, listener)).hasMessage("RERO_INVALID_RESPONSE");
        script.add(succeeded(new Result("FEE_OR_CHARGE", "x", List.of(SERVED), new BigDecimal("1.5"), SelfCheck.PASS, "")));
        assertThatThrownBy(() -> rero.run(job, listener)).hasMessage("RERO_INVALID_RESPONSE");
        script.add(succeeded(new Result("FEE_OR_CHARGE", "x", List.of(SERVED), new BigDecimal("0.5"), null, "")));
        assertThatThrownBy(() -> rero.run(job, listener)).hasMessage("RERO_INVALID_RESPONSE");
        script.add(new ReroRun(UUID.randomUUID(), UUID.randomUUID(), Status.RUNNING, Stage.DRAFT, null, 0, 0, null, null));
        assertThatThrownBy(() -> rero.run(job, listener)).hasMessage("RERO_INVALID_RESPONSE"); // another job's run
    }

    @Test
    void aFailedRunMapsItsCodeAndKeepsInvocationIds() {
        script.add(new ReroRun(UUID.randomUUID(), job.getId(), Status.FAILED, Stage.DONE, "UNKNOWN_OUTCOME", 2, 1,
            List.of("inv-1"), null));
        assertThatThrownBy(() -> rero.run(job, listener)).isInstanceOfSatisfying(ReroCallException.class, e -> {
            assertThat(e.code()).isEqualTo("RERO_UNKNOWN_OUTCOME");
            assertThat(e.gatewayInvocationIds()).containsExactly("inv-1");
            assertThat(e.retryable()).isTrue();
        });
        script.add(new ReroRun(UUID.randomUUID(), job.getId(), Status.FAILED, Stage.DONE, "SOMETHING_ODD", 0, 0, null, null));
        assertThatThrownBy(() -> rero.run(job, listener)).hasMessage("RERO_UNRECOGNISED");
    }

    @Test
    void aRunThatNeverEndsHitsOurDeadline() {
        // script empty: every poll is RUNNING; the sleeper advances the clock 100 ms per poll
        assertThatThrownBy(() -> rero.run(job, listener)).hasMessage("RERO_DEADLINE_EXCEEDED");
        assertThat(clock.instant()).isAfterOrEqualTo(Instant.EPOCH.plusSeconds(6));
    }

    @Test
    void transientPollFailuresAreToleratedThenGiveUp() {
        script.add(running());
        script.add(new ReroCallException(ReroCallException.UNAVAILABLE));
        script.add(succeeded(result(List.of(SERVED), SelfCheck.PASS)));
        assertThat(rero.run(job, listener).draft()).isNotNull();

        script.add(running());
        for (int i = 0; i < 3; i++) {
            script.add(new ReroCallException(ReroCallException.UNAVAILABLE));
        }
        assertThatThrownBy(() -> rero.run(job, listener)).hasMessage("RERO_UNAVAILABLE");
    }

    @Test
    void aStartFailureIsTyped() {
        script.add(new ReroCallException(ReroCallException.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> rero.run(job, listener)).hasMessage("RERO_IDEMPOTENCY_KEY_REUSED");
    }

    // ------------------------------------------------------------------ helpers

    private ReroRun running() {
        return new ReroRun(UUID.randomUUID(), job.getId(), Status.RUNNING, Stage.RETRIEVE, null, 1, 0, null, null);
    }

    private ReroRun succeeded(Result r) {
        return new ReroRun(UUID.randomUUID(), job.getId(), Status.SUCCEEDED, Stage.DONE, null, 2, 2, List.of("a", "b"), r);
    }

    private static Result result(List<String> cited, SelfCheck check) {
        return new Result("FEE_OR_CHARGE", "Fee of 500 on account 12345678901234", cited, new BigDecimal("0.9"), check, "ok");
    }

    private static InvestigationJob job() {
        InvestigationJob j = new InvestigationJob("TENANT_U", UUID.randomUUID(), "maker", null, InvestigationMode.RERO);
        j.limitToolCalls(8);
        try {
            Field id = InvestigationJob.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(j, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return j;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(java.time.ZoneId zone) { return this; }

        @Override
        public Instant instant() { return now; }
    }
}
