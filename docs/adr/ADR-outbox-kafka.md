# ADR: transactional outbox and Kafka intake

Status: **implemented (Phase 4F), off by default, recorded 2026-11-15.** Code: `infrastructure/messaging/`
(`OutboxRecorder`, `OutboxRelay`, `BreakIntakeListener`, `KafkaConfig`), migration `V5__outbox.sql`. Tests:
`KafkaOutboxIT` (real Kafka via Testcontainers).

## 1. Context

Downstream systems (reporting, notifications, a later recommender) need to know when a break is created, a proposal
is routed and a break is resolved, without the request path depending on a broker being up. Upstream systems need
to push breaks in without calling the REST API. The invariant to keep is that a state change and the event that
announces it either both happen or neither does.

## 2. Decision

### Outbound: transactional outbox

`BreakService`, `BreakIngestionService`, `ProposalService`, `ResolutionCommitService` and `DailyDigestService` write
an `outbox_event` row **in the same database transaction** as the state change (`OutboxRecorder`). Nothing talks to
Kafka inside a request. `OutboxRelay` polls the unpublished tail every second in batches of 50 using
`SELECT ... FOR UPDATE SKIP LOCKED`, publishes to `reconcile.break.events` with key = aggregate id (so one break's
events stay ordered in one partition) and headers `event-id`, `event-type`, `tenant-id`, and marks the row
published only after the broker acknowledges. A failed send stops the batch to keep per-key order, increments
`attempts` and is retried on the next tick.

Delivery is therefore **at-least-once**: a crash between ack and commit re-sends. Consumers dedupe on `event-id`.
The project never claims exactly-once (the integration programme's Q0 removed that wording wherever it appeared).

Events: `BREAK_CREATED`, `PROPOSAL_ROUTED`, `BREAK_RESOLVED`, `DAILY_DIGEST_READY`. Amounts travel as strings, like
the REST requests.

### Inbound: break intake

`BreakIntakeListener` consumes `reconcile.break.intake` (group `reconcile-ai-intake`), payload
`{tenantId, breakReference, ledgerAmount, externalAmount, currency}`. It creates the break through the same
`BreakIngestionService` as REST, inside `TenantScope.run(tenantId, ...)` so Row-Level Security applies. Idempotent on
(tenant, breakReference): a redelivery is a no-op. A malformed message is not retried and goes to
`reconcile.break.intake.dlt`; a transient failure (database down) is retried with backoff and then dead-lettered.

Tenant for an intake message comes from the message, not from a JWT, because there is no caller. This is the one
place a tenant id enters from data rather than from a token; the topic is therefore an internal, producer-authenticated
topic, never exposed to clients, and the listener runs under an explicit named scope.

### Off by default

`KAFKA_ENABLED=false` creates no producer, consumer or relay; the REST path is unaffected and outbox rows still get
written (they are relayed when Kafka is turned on). The `kafka` Compose profile provides a broker locally.

## 3. Why not publish directly from the service, or use Debezium

Direct publish inside the transaction is a dual write: a broker timeout after the commit loses the event, before the
commit loses the state. Change-data-capture (Debezium) gives the same guarantee without a relay but adds a
connector platform to operate; for one table and one topic the poller is simpler and testable in-process.

## 4. Observability

`reconcile_outbox_published_total`, `reconcile_intake_total{outcome}`, the `OutboxBacklogGrowing` alert on the
unpublished count; the `idx_outbox_unpublished` partial index keeps the relay's scan to the unpublished tail.

## 5. Limits

- No consumer lives in this repository; the dedupe rule is a contract stated in the README, not enforced here.
- The relay is per instance with `SKIP LOCKED`; ordering holds per key, not globally.
- Published rows are never purged; a retention job is a later operational task.
