package com.reconcileai.infrastructure.streaming;

import com.reconcileai.application.CapacityExceededException;
import com.reconcileai.application.investigation.InvestigationProgressPublisher;
import com.reconcileai.domain.InvestigationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-Sent Events fan-out of investigation progress.
 *
 * <pre>
 *  runner thread: stage TX commits -> publish(job) -> for each subscriber of job.id (same tenant) -> send "progress"
 *                                                     terminal? -> complete() and drop subscribers
 *  client:        GET /api/v1/investigations/{id}/events (Bearer JWT, fetch-based; EventSource cannot send headers)
 * </pre>
 *
 * <h2>Why SseEmitter (Spring MVC) and not WebFlux</h2>
 * The app is servlet-based (locked: no WebFlux). SseEmitter holds an async servlet context, not a request thread.
 *
 * <h2>Scope and honest limits</h2>
 * The registry is in-memory, per instance. With several instances behind a load balancer, a client only receives
 * events published by the node that runs the job, unless it is routed to that node. That is why every subscribe
 * starts with a DB snapshot, streams expire, and polling remains a first-class API. Cross-node fan-out
 * (Redis/Postgres LISTEN) is not implemented.
 */
@Component
public class SseProgressBroadcaster implements InvestigationProgressPublisher {

    private static final Logger log = LoggerFactory.getLogger(SseProgressBroadcaster.class);

    private record Subscriber(SseEmitter emitter, String tenantId) {}

    private final Map<UUID, List<Subscriber>> byJob = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final StreamingProperties props;

    public SseProgressBroadcaster(StreamingProperties props) {
        this.props = props;
    }

    /** Registers a stream for a job the caller has ALREADY been authorised to read (tenant-scoped load, else 404). */
    public SseEmitter subscribe(UUID jobId, String tenantId) {
        List<Subscriber> subs = byJob.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        if (subs.size() >= props.maxSubscribersPerJob() || total.incrementAndGet() > props.maxSubscribers()) {
            if (subs.size() < props.maxSubscribersPerJob()) {
                total.decrementAndGet();
            }
            throw new CapacityExceededException("Too many open progress streams; poll the job instead");
        }
        SseEmitter emitter = new SseEmitter(props.emitterTimeoutMs());
        Subscriber sub = new Subscriber(emitter, tenantId);
        subs.add(sub);
        Runnable cleanup = () -> remove(jobId, sub);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> { cleanup.run(); emitter.complete(); });
        emitter.onError(t -> cleanup.run());
        return emitter;
    }

    /** Sends the current DB state to ONE new subscriber (closes the subscribe-after-finish race). */
    public void sendSnapshot(SseEmitter emitter, InvestigationJob job) {
        if (send(emitter, ProgressEvent.of(job)) && job.isTerminal()) {
            emitter.complete();
        }
    }

    @Override
    public void publish(InvestigationJob job) {
        List<Subscriber> subs = byJob.get(job.getId());
        if (subs == null || subs.isEmpty()) {
            return;
        }
        ProgressEvent event = ProgressEvent.of(job);
        for (Subscriber sub : subs) {
            if (!sub.tenantId().equals(job.getTenantId())) {
                continue; // defence in depth: subscribe already enforced tenant; never cross the boundary here
            }
            if (send(sub.emitter(), event) && job.isTerminal()) {
                sub.emitter().complete();
            }
        }
    }

    /** Comment frames keep idle connections alive through proxies and detect dead clients early. */
    @Scheduled(fixedDelayString = "${reconcile.streaming.heartbeat-interval-ms:15000}")
    void heartbeat() {
        byJob.values().forEach(subs -> subs.forEach(sub -> {
            try {
                sub.emitter().send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                sub.emitter().completeWithError(e);
            }
        }));
    }

    int openStreams() {
        return total.get();
    }

    private boolean send(SseEmitter emitter, ProgressEvent event) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(event, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) { // client gone, or emitter already completed
            log.debug("dropping SSE subscriber for job {}: {}", event.jobId(), e.toString());
            emitter.completeWithError(e);
            return false;
        }
    }

    private void remove(UUID jobId, Subscriber sub) {
        List<Subscriber> subs = byJob.get(jobId);
        if (subs != null && subs.remove(sub)) {
            total.decrementAndGet();
            if (subs.isEmpty()) {
                byJob.remove(jobId, subs);
            }
        }
    }
}
