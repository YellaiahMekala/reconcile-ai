# ADR: Java 21 virtual threads for request handling

Status: **implemented 2026-11-15.** `spring.threads.virtual.enabled=true` in `application.yml`; verified by
`VirtualThreadsTenantIsolationIT` and `TenantScopeVirtualThreadTest`.

## 1. Context

Almost every request in this service waits on I/O: Postgres (RLS-scoped queries), the model provider, Presidio, S3,
and from Q1 the EKA edge and ai-gateway. On platform threads each waiting request holds one of Tomcat's 200 threads
and about 1 MB of stack while it idles. The project is locked to Spring MVC with `SseEmitter` and must never add
WebFlux, so the reactive route to higher concurrency is closed by rule.

## 2. Decision

Enable Spring Boot's virtual-thread support. With the one flag, Boot runs Tomcat request processing, `@Scheduled`
jobs and the default `applicationTaskExecutor` on virtual threads. No production code changed.

The **investigation executor is left as a bounded platform pool** (`InvestigationExecutorConfig`: core 2, max 4,
queue 50, `AbortPolicy`). Its full queue is what turns overload into a `503` on `POST /investigations`. A
thread-per-task virtual executor has no queue to fill, so switching it would silently remove that back-pressure and
would let an unbounded number of jobs hit a rate-limited model provider at once. Concurrency there is a product
limit, not a thread cost.

## 3. The one real risk: tenant identity on a reused or inherited thread

Identity is thread-local in two places: `SecurityContextHolder` (the validated JWT, read by `TenantContext`) and
`TenantScope` (the explicit scope for background work, which also drives Postgres RLS). Both are plain
`ThreadLocal`s, not `InheritableThreadLocal`s, and Spring Security's holder strategy is the default
`MODE_THREADLOCAL`. Virtual threads are created per request and discarded, so there is no pool reuse, and because
nothing is inheritable a thread created during a request does not receive the request's identity.

Proof, both release-blocking like the existing cross-tenant test:

- `VirtualThreadsTenantIsolationIT`: two fresh tenants, 120 interleaved `GET /api/v1/breaks` calls from 16 client
  threads; every response contains only its own tenant's break. A test-scoped filter records the handling thread of
  each request, and the test fails if any request ran on a platform thread, so it cannot pass with the flag off.
- `TenantScopeVirtualThreadTest`: on virtual threads the scope is visible only inside `call`, is cleared after it, is
  not inherited by a thread started inside the scope, and 500 interleaved scopes that yield mid-body never bleed.

## 4. Pinning

In JDK 21 a virtual thread pins its carrier while it blocks inside `synchronized`. The `synchronized` blocks in this
code (`AgentRunContext`, `TokenBucketRateLimiter`) guard in-memory fields only and do no I/O, so any pinning is
momentary. JDBC and HTTP calls happen outside any monitor. Re-check this section whenever a new `synchronized` block
wraps a blocking call; `-Djdk.tracePinnedThreads=full` shows offenders.

## 5. Alternatives considered

- **WebFlux / reactive stack:** rejected by the standing MVC-only rule; it would also force a rewrite of the
  blocking JPA and Spring AI paths.
- **Larger platform thread pool:** more memory, same idle waiting; does not change the shape of the problem.
- **Virtual threads for the investigation executor too:** rejected, see section 2.

## 6. What was not measured

No load benchmark is published. The claim is "request handling runs on virtual threads with tenant isolation
proven", not "N times more throughput". A throughput comparison needs a stable load generator and a fixed-latency
model stub; it can be a later evidence item.
