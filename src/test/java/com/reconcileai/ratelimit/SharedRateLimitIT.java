package com.reconcileai.ratelimit;

import com.reconcileai.infrastructure.security.RateLimiter;
import com.reconcileai.infrastructure.security.RedisTokenBucketRateLimiter;
import com.reconcileai.infrastructure.security.TokenBucketRateLimiter;
import com.reconcileai.support.AbstractPostgresIT;
import com.reconcileai.support.ApiTestSupport;
import com.reconcileai.support.JwtTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * {@code reconcile.ratelimit.store=redis} in the running application: a second "replica" (a limiter on the same
 * Redis, outside this JVM's bucket) spends the tenant's burst, and this instance then answers 429 on the first
 * request it sees. With the per-JVM bucket it would have allowed two more.
 */
class SharedRateLimitIT extends AbstractPostgresIT {

    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void sharedLimiter(DynamicPropertyRegistry r) {
        r.add("reconcile.ratelimit.enabled", () -> "true");
        r.add("reconcile.ratelimit.capacity", () -> "2");
        r.add("reconcile.ratelimit.refill-per-minute", () -> "1");
        r.add("reconcile.ratelimit.store", () -> "redis");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    @Autowired
    RateLimiter appLimiter;

    @Autowired
    RedisConnectionFactory redis;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void theApplicationUsesTheSharedBucket() {
        assertThat(appLimiter).isInstanceOf(RedisTokenBucketRateLimiter.class);
    }

    @Test
    void aBurstSpentOnAnotherReplicaIsRefusedHere() {
        String tenant = "TENANT_RL_SHARED";
        String bearer = "Bearer " + JwtTestSupport.mint("a1", tenant, List.of("RECON_ANALYST"));
        String breakId = ApiTestSupport.createBreak(bearer, "100.00", "90.00");

        RateLimiter otherReplica = new RedisTokenBucketRateLimiter(new StringRedisTemplate(redis), 2, 1,
            new TokenBucketRateLimiter(2, 1, Clock.systemUTC()), new SimpleMeterRegistry());
        assertThat(otherReplica.tryAcquire(tenant).allowed()).isTrue();
        assertThat(otherReplica.tryAcquire(tenant).allowed()).isTrue();

        given().header("Authorization", bearer).contentType(ContentType.JSON).body("{}")
            .when().post("/api/v1/breaks/" + breakId + "/investigations")
            .then().statusCode(429).header("Retry-After", notNullValue()).body("code", equalTo("RATE_LIMITED"));

        // A tenant the other replica did not touch still has its full burst here.
        String other = "Bearer " + JwtTestSupport.mint("b1", "TENANT_RL_SHARED_2", List.of("RECON_ANALYST"));
        String otherBreak = ApiTestSupport.createBreak(other, "100.00", "90.00");
        given().header("Authorization", other).contentType(ContentType.JSON).body("{}")
            .when().post("/api/v1/breaks/" + otherBreak + "/investigations")
            .then().statusCode(409).body("code", equalTo("EVIDENCE_NOT_READY"));
    }
}
