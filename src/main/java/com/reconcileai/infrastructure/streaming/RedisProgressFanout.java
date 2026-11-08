package com.reconcileai.infrastructure.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * Cross-instance SSE fan-out over Redis pub/sub ({@code reconcile.streaming.fanout=redis}).
 *
 * <pre>
 *  node A runs job ─► publish(tenant, event) ─► Redis channel "reconcile:progress"
 *                                                 ├─► node A listener ─► deliver to A's local streams
 *                                                 └─► node B listener ─► deliver to B's local streams
 * </pre>
 * The originating node also receives its own message, so delivery has ONE path (no double events locally).
 *
 * <h2>Why pub/sub (not Streams, not Kafka, not Postgres LISTEN/NOTIFY)</h2>
 * Progress events are ephemeral: a client that misses one gets the current state from the DB snapshot on
 * (re)subscribe, and polling stays available. Fire-and-forget pub/sub is therefore enough; durable Streams or Kafka
 * would add retention and consumer-group bookkeeping for data nobody replays. LISTEN/NOTIFY would also work but
 * holds a dedicated DB connection per node, and Redis is already in the stack for the semantic cache.
 *
 * <h2>Failure behaviour</h2>
 * If Redis is down, {@link #publish} returns false and the broadcaster delivers locally: single-node behaviour,
 * never an error for the investigation itself.
 */
public class RedisProgressFanout {

    public static final String CHANNEL = "reconcile:progress";
    private static final Logger log = LoggerFactory.getLogger(RedisProgressFanout.class);

    /** Wire format: the tenant travels with the event so every node re-checks it against its subscribers. */
    public record Envelope(String tenantId, ProgressEvent event) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public RedisProgressFanout(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.meters = meters;
    }

    public boolean publish(String tenantId, ProgressEvent event) {
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(new Envelope(tenantId, event)));
            meters.counter("reconcile.sse.fanout", "result", "published").increment();
            return true;
        } catch (Exception e) {
            meters.counter("reconcile.sse.fanout", "result", "error").increment();
            log.warn("SSE fan-out publish failed, delivering locally only: {}", e.toString());
            return false;
        }
    }

    Envelope decode(byte[] body) throws java.io.IOException {
        return json.readValue(new String(body, StandardCharsets.UTF_8), Envelope.class);
    }

    @Configuration
    @ConditionalOnProperty(prefix = "reconcile.streaming", name = "fanout", havingValue = "redis")
    static class Wiring {

        @Bean
        RedisProgressFanout redisProgressFanout(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters) {
            return new RedisProgressFanout(redis, json, meters);
        }

        @Bean
        RedisMessageListenerContainer progressListenerContainer(RedisConnectionFactory cf, RedisProgressFanout fanout,
                                                                SseProgressBroadcaster broadcaster) {
            RedisMessageListenerContainer c = new RedisMessageListenerContainer();
            c.setConnectionFactory(cf);
            c.addMessageListener((message, pattern) -> {
                try {
                    Envelope env = fanout.decode(message.getBody());
                    broadcaster.deliver(env.tenantId(), env.event());
                } catch (Exception e) {
                    log.warn("ignoring malformed progress message: {}", e.toString());
                }
            }, new ChannelTopic(CHANNEL));
            return c;
        }
    }
}
