# ADR-Q1: EKA knowledge is a deterministic pre-step of the single-call investigation

- **Date:** 2026-11-18
- **Status:** Accepted
- **Scope:** platform Q1.1 to Q1.5 (`platform/docs/TDD.md` section 4, `IMPLEMENTATION_PLAN.md` Q1)

## Context

The plan's Q1.5 proposed a read-only `searchKnowledge` tool for the agent. Since the Q2 review fixes (`d0da196`), the
agent and supervised modes are refused with `409 MODE_NOT_GOVERNED` while the ai-gateway route is on, because their
model calls go straight to the provider. ai-gateway has no tool calling (gateway ADR-18), so an agent tool cannot run
on the governed path without changing the gateway.

## Decision

1. **Knowledge is fetched by the service, not chosen by the model.** In the single-call mode, after break evidence is
   retrieved and before the one model call, reconcile-ai makes one `POST /api/v1/search` to the EKA edge for the job's
   tenant. The model call stays a single call, so it can go through ai-gateway unchanged.
2. **Procedures are reference data, never evidence.** Accepted hits are rendered in a separate
   `PROCEDURES CONSULTED` block with their own markers, and the system prompt gains rule 7 ("never cite"). Their refs
   (`kb:<EKA chunk id>`) are stored in `knowledge_refs`, apart from `retrieved_chunk_ids`, so the citation verifier can
   never accept them (`KnowledgeNeverCitedTest`). Without hits the prompt is byte-for-byte the pre-Q1 prompt.
3. **Four outcomes, stored and audited.** `OK`, `EMPTY`, `UNAVAILABLE`, `REJECTED` with a short reason code go to the job
   row, the job API and a `KNOWLEDGE_CONSULTED` audit entry (codes and counts only, no passage text).
4. **Fail-open by default.** `KNOWLEDGE_FAIL_CLOSED=false`: an unavailable EKA leaves a visible `UNAVAILABLE` on the job
   and the investigation continues. `true`: the job fails retryably (`KNOWLEDGE_UNAVAILABLE`, break
   `FAILED_RETRYABLE`, no model call). Auto-resolution stays off either way; a person still approves every proposal.
5. **Per-tenant identity, no shared client.** EKA isolates documents per caller identity (spike S-2), so each tenant has
   its own platform-realm client (`reconcile.knowledge.tenants`), the token's `tenant_id` must equal the job's tenant
   exactly, and a tenant without a client sends nothing (`TENANT_CREDENTIALS_MISSING`).
6. **Agent modes do not use knowledge.** Enforced by `KnowledgeArchitectureTest`.

## Safety controls on the call

| Control | Where |
|---|---|
| Query: generic procedure vocabulary plus the analyst note; no break reference or amounts; PII masked; cut to EKA's 1000 chars | `InvestigationJobRunner.knowledgeQuery`, `EkaKnowledgeClient` |
| Hits: injection scan (dropped, rule name logged, never the text), PII mask, 800 chars per hit, 2400 per prompt | `EkaKnowledgeClient.screen` |
| Timeouts 2 s connect / 5 s request, no retry, circuit breaker (50 % of 10 calls, 30 s open) | `KnowledgeProperties` |
| 401/403 = `UNAVAILABLE AUTH`, not counted by the breaker (configuration fault, EKA is up) | `EkaKnowledgeClient` |
| Only `/api/v1/search` exists in main code; never EKA chat or conversations | `KnowledgeArchitectureTest` |

## Consequences

- Q1 works with the gateway route on or off, and adds no new path around the gateway.
- The model sees procedures only in the single-call mode. A governed agent path, if ever needed, means tool calling in
  ai-gateway first: a separate decision.
- EKA document admin endpoints in reconcile-ai (plan Q1.5, second half) are **not built**: procedures are managed in
  EKA directly. Revisit if a role needs it.
- Proof: `EkaKnowledgeClientTest` (stub contract from EKA `openapi.json`), `KnowledgeOutcomeIT`,
  `KnowledgeFailClosedIT`, `KnowledgeNeverCitedTest`, `KnowledgeArchitectureTest`. Real EKA isolation and the
  concurrency measurement are `RealEkaIsolationIT` (tag `eka`, opt-in, not in CI).
