// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
package com.reconcileai.ratelimit;

import com.reconcileai.infrastructure.security.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    /** Clock the test moves forward by hand. */
    static class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void allowsBurstThenRejects() {
        var limiter = new TokenBucketRateLimiter(3, 6, new ManualClock());
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("t1").allowed());
        }
        var fourth = limiter.tryAcquire("t1");
        assertFalse(fourth.allowed());
        assertEquals(10, fourth.retryAfterSeconds()); // 6 per minute = one token every 10 s
    }

    @Test
    void refillsOverTime() {
        var clock = new ManualClock();
        var limiter = new TokenBucketRateLimiter(1, 6, clock);
        assertTrue(limiter.tryAcquire("t1").allowed());
        assertFalse(limiter.tryAcquire("t1").allowed());
        clock.advance(Duration.ofSeconds(10));
        assertTrue(limiter.tryAcquire("t1").allowed());
    }

    @Test
    void neverRefillsPastCapacity() {
        var clock = new ManualClock();
        var limiter = new TokenBucketRateLimiter(2, 60, clock);
        clock.advance(Duration.ofHours(1));
        assertTrue(limiter.tryAcquire("t1").allowed());
        assertTrue(limiter.tryAcquire("t1").allowed());
        assertFalse(limiter.tryAcquire("t1").allowed());
    }

    @Test
    void tenantsHaveSeparateBuckets() {
        var limiter = new TokenBucketRateLimiter(1, 1, new ManualClock());
        assertTrue(limiter.tryAcquire("A").allowed());
        assertFalse(limiter.tryAcquire("A").allowed());
        assertTrue(limiter.tryAcquire("B").allowed());
    }

    @Test
    void rejectsNonsenseConfig() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0, 10, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(10, 0, Clock.systemUTC()));
    }
}
