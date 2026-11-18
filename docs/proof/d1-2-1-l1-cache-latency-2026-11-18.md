# D1.2.1: Caffeine L1 vs Redis L2 hit latency (2026-11-18)

Status: **measured, free, one laptop.** Test: `TieredSemanticCacheIT.measureL1VersusL2HitLatency` (real Redis 7 in
Testcontainers on Docker Desktop, Windows 11, JDK 21). No model call; the test embedding model is the local fake.

## What was measured

The same cached draft looked up 500 times through each tier, after 50 warm-up lookups, alternating L1 and L2:

- **L1 hit**: `TieredSemanticCache.lookup` served from the Caffeine heap (key hash + map read + metric).
- **L2 hit**: `RedisSemanticCache.lookup`, exact tier (GET of the exact key, HGET of the evidence snapshot, HGET of
  the draft, JSON parse): three Redis round trips.

## Result

| Tier | p50 | p95 |
|---|---|---|
| L1 (Caffeine, in heap) | 159.5 us | 323.9 us |
| L2 (Redis exact hit) | 6185.8 us | 17497.3 us |

About 39x faster at p50 on this machine. The L1 figure includes a SHA-256 of the prompt, which dominates it.

## Limits

- One run, one laptop. Redis runs in Docker Desktop's VM, so each round trip pays VM networking; Redis on the same host
  network in production would be closer to 1 ms per exact hit. The ratio, not the absolute number, is the claim.
- A cache hit already avoids a model call of about 2.6 to 3.3 s (see the Q4.3 reports). L1 saves milliseconds on top of
  that; its main value is fewer Redis calls under repeated reads, not user-visible latency.
- Semantic (non-exact) L2 hits were not timed; they also embed the prompt and scan up to N entries, so they are slower
  than the exact hit above. An L1 entry promoted from a semantic hit is served at L1 speed.
