package com.reconcileai.streaming;

import com.reconcileai.infrastructure.streaming.ProgressEvent;
import com.reconcileai.infrastructure.streaming.SseProgressBroadcaster;
import com.reconcileai.infrastructure.streaming.StreamingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.UUID;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression for a CI-only flake: the job's terminal event and the subscribe-time snapshot are sent from two threads.
 * Whichever loses finds the emitter already completed. That is benign and must NOT be turned into completeWithError:
 * Spring then runs an async error dispatch that writes a JSON error body into a chunked text/event-stream response,
 * and the client sees "chunked transfer encoding, state: READING_LENGTH".
 */
class SseProgressBroadcasterTest {

    private final SseProgressBroadcaster broadcaster = new SseProgressBroadcaster(
        new StreamingProperties(60_000, 10, 5, 15_000, "local"), mock(ObjectProvider.class));

    private static ProgressEvent terminalEvent(UUID jobId) {
        return new ProgressEvent(jobId, UUID.randomUUID(), "SUCCEEDED", "COMPLETED", null, null, true, Instant.now());
    }

    /** Spring's Handler and initialize() are package-private; a recording proxy plays the servlet response. */
    private static List<String> attachResponse(SseEmitter emitter) throws Exception {
        List<String> calls = new ArrayList<>();
        Class<?> handlerType = Class.forName("org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType}, (proxy, m, args) -> {
            calls.add(m.getName());
            return null;
        });
        var init = emitter.getClass().getSuperclass().getDeclaredMethod("initialize", handlerType);
        init.setAccessible(true);
        init.invoke(emitter, handler);
        return calls;
    }

    @Test
    void sendingToAnAlreadyCompletedEmitterIsNotAnError() throws Exception {
        UUID jobId = UUID.randomUUID();
        SseEmitter emitter = broadcaster.subscribe(jobId, "T");
        emitter.complete();                                   // the other thread already delivered the terminal event

        broadcaster.deliver("T", terminalEvent(jobId));       // the losing thread of the race

        assertThat(attachResponse(emitter)).contains("complete").doesNotContain("completeWithError");
    }

    @Test
    void heartbeatOnAnAlreadyCompletedEmitterIsNotAnError() throws Exception {
        UUID jobId = UUID.randomUUID();
        SseEmitter emitter = broadcaster.subscribe(jobId, "T");
        emitter.complete();

        var heartbeat = SseProgressBroadcaster.class.getDeclaredMethod("heartbeat");
        heartbeat.setAccessible(true);
        heartbeat.invoke(broadcaster);

        assertThat(attachResponse(emitter)).doesNotContain("completeWithError");
    }
}
