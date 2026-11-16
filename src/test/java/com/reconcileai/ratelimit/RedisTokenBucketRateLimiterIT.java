package com.reconcileai.ratelimit;

import com.reconcileai.infrastructure.security.RateLimiter;
import com.reconcileai.infrastructure.security.RedisTokenBucketRateLimiter;
import com.reconcileai.infrastructure.security.TokenBucketRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared bucket against a real Redis: two limiter instances (two replicas) draw from one bucket, the
 * read-refill-take step is atomic under concurrency, and an unreachable Redis falls back to the local bucket.
 */
class RedisTokenBucketRateLimiterIT {

    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory factory;

    @BeforeAll
    static void start() {
        REDIS.start();
        factory = connect(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stop() {
        if (factory != null) {
            factory.destroy();
        }
        REDIS.stop();
    }

    private static LettuceConnectionFactory connect(String host, int port) {
        LettuceConnectionFactory f = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        f.afterPropertiesSet();
        return f;
    }

    private static RateLimiter replica(LettuceConnectionFactory f, int capacity, int perMinute, SimpleMeterRegistry meters) {
        return new RedisTokenBucketRateLimiter(new StringRedisTemplate(f), capacity, perMinute,
            new TokenBucketRateLimiter(capacity, perMinute, Clock.systemUTC()), meters);
    }

    @Test
    void twoReplicasShareOneBucket() {
        String tenant = "TENANT_" + UUID.randomUUID();
        RateLimiter node1 = replica(factory, 3, 1, new SimpleMeterRegistry());
        RateLimiter node2 = replica(factory, 3, 1, new SimpleMeterRegistry());

        assertThat(node1.tryAcquire(tenant).allowed()).isTrue();
        assertThat(node2.tryAcquire(tenant).allowed()).isTrue();
        assertThat(node1.tryAcquire(tenant).allowed()).isTrue();
        RateLimiter.Decision fourth = node2.tryAcquire(tenant);
        assertThat(fourth.allowed()).as("per-JVM buckets would have allowed 6").isFalse();
        assertThat(fourth.retryAfterSeconds()).isBetween(1L, 60L);

        assertThat(node1.tryAcquire("OTHER_" + tenant).allowed()).as("another tenant has its own bucket").isTrue();
    }

    @Test
    void concurrentRequestsNeverOverspend() throws Exception {
        String tenant = "TENANT_" + UUID.randomUUID();
        List<RateLimiter> nodes = List.of(replica(factory, 10, 1, new SimpleMeterRegistry()),
            replica(factory, 10, 1, new SimpleMeterRegistry()));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                RateLimiter node = nodes.get(i % 2);
                calls.add(() -> node.tryAcquire(tenant).allowed());
            }
            long allowed = 0;
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                allowed += f.get() ? 1 : 0;
            }
            assertThat(allowed).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theKeyExpiresOnceTheBucketWouldBeFull() {
        String tenant = "TENANT_" + UUID.randomUUID();
        replica(factory, 2, 60, new SimpleMeterRegistry()).tryAcquire(tenant);

        Long ttl = new StringRedisTemplate(factory).getExpire("reconcile:ratelimit:investigations:" + tenant);
        assertThat(ttl).as("2 tokens at 60/min refill in 2 s, plus 1 s").isBetween(1L, 3L);
    }

    @Test
    void unreachableRedisFallsBackToTheLocalBucketAndIsCounted() {
        LettuceConnectionFactory dead = connect("127.0.0.1", 1);
        try {
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            RateLimiter limiter = replica(dead, 1, 1, meters);

            assertThat(limiter.tryAcquire("TENANT_X").allowed()).isTrue();
            assertThat(limiter.tryAcquire("TENANT_X").allowed()).as("the local bucket still limits").isFalse();
            assertThat(meters.get("reconcile.ratelimit.redis.fallback").counter().count()).isEqualTo(2.0);
        } finally {
            dead.destroy();
        }
    }
}
