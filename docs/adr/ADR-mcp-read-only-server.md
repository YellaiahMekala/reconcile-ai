# ADR: read-only MCP server, tenant from the caller's JWT

Status: **implemented (platform D1.2.2), off by default, recorded 2026-11-18.** Code: `infrastructure/mcp/`
(`McpServerConfig`, `McpReadTools`, `McpProperties`, `PlainTextResultConverter`), one matcher in `SecurityConfig`.
Tests: `McpReadOnlyServerIT`, `McpDisabledByDefaultIT`.

## 1. Context

MCP clients (an IDE assistant, another agent) want to read reconciliation data. The investigation agent already has
read-only tools (`ReconTools`), but they are bound to one run and one break. An MCP server is a new way in, so the
tenant and role rules must be decided before any code: an MCP request must never be a way to read another tenant's data
or to change anything.

## 2. Decision

### Tenant and identity

- The tenant comes **only** from the caller's validated JWT (`tenant_id` claim), through the same resource-server chain
  and `TenantContext` as the REST API (RS256 pinned or IdP issuer + audience, `tenant_id` required, RERO run tokens
  refused). No tool has a tenant argument. A tool argument, header or MCP session value can never select a tenant.
- Every query is tenant-scoped (`findByIdAndTenantId`, `findByTenantId...`) and Postgres RLS applies underneath. An id
  of another tenant reads as `NOT_FOUND`, never "forbidden", so ids are not confirmed.

### Roles

- HTTP layer: `/mcp` needs `RECON_ANALYST`, `APPROVER_L1` or `APPROVER_L2`. No token: 401. Other roles (`SRE_ADMIN`,
  none): 403. `SRE_ADMIN` is ops-only and does not read business data, as with evidence search.
- Each tool re-checks the role (defence in depth) and refuses with a tool error.

### Tools (read-only)

Reused from the agent tools and repositories; the agent's text formatters are shared so both see the same data:

| Tool | Reads | Source |
|---|---|---|
| `get_break_summary(breakId)` | reference, amounts, variance, currency, status | `BreakItemRepository.findByIdAndTenantId`, `ReconTools.breakSummaryText` |
| `list_evidence(breakId)` | file name, status, chunk count | `EvidenceRepository`, `ReconTools.evidenceListText` |
| `list_recent_breaks(limit<=20)` | id, reference, status, variance band, currency | `BreakItemRepository.findByTenantIdOrderByCreatedAtDesc` |
| `find_similar_resolved_breaks(rootCause)` | up to 5 approved proposals: band, action | `ResolutionProposalRepository.findSimilarResolved`, `ReconTools.similarResolvedText` |

Not exposed: evidence search (it returns passage text built for a model prompt and needs the break's retrieval
context), evidence download, audit, anything that starts an investigation, approves, or writes. The server is the only
source of Spring AI `ToolCallback`s in the app, and an IT asserts the exact tool list.

### Per call

Role check, per-tenant rate limit, run, injection screen of the result, PII mask, length cap (4000 chars), metric
`reconcile.mcp.tool.calls{tool,outcome}`, then an audit event in the tenant's hash chain:
`MCP_TOOL_CALLED`, actor = JWT subject, details = tool, outcome, result size, duration. **Arguments and results are not
audited** (ids and text can be sensitive, and the audit must not become a copy of the data). Refusals
(`DENIED`, `RATE_LIMITED`) are audited too.

### Rate and budget limit

A separate per-JVM token bucket keyed `mcp:<tenant>` (default 30 burst, 30 per minute;
`reconcile.mcp.capacity`, `refill-per-minute`). Separate from the investigation limiter, so MCP reads cannot use up
the investigation budget or the reverse. No tool calls a model, so there is no token cost to budget.

### Transport

Spring AI 1.1.8 `spring-ai-starter-mcp-server-webmvc` (servlet, no WebFlux added; `spring-webflux` was already on the
classpath only as a transitive of the OpenAI client). Protocol **STATELESS** (streamable HTTP without a session):
`POST /mcp`, JSON reply. On a servlet server Spring AI sets `immediateExecution`, so a tool runs on the request thread
and sees that request's JWT. The SSE and session-based streamable modes are refused at startup: they can run a tool
away from the request that carries the token.

### Off by default

`MCP_SERVER_ENABLED=false` (`spring.ai.mcp.server.enabled`). Off: no server bean, no tool bean, `/mcp` is 404.

## 3. CVE-2026-35568 (mcp-core 0.18.3) becomes relevant when the server is on

The finding is a DNS-rebinding issue: mcp-core before 1.0.0 does not validate the `Origin` header on its HTTP server
transports. Before D1.2.2 reconcile-ai ran no MCP server, so the `.trivyignore` entry said "not exploitable here".
**With `MCP_SERVER_ENABLED=true` the vulnerable code path is live.** The fix (mcp-core 1.0.0) is not available inside the
locked Spring AI 1.1.8 BOM. Mitigations in place:

1. Every `/mcp` request needs a valid bearer JWT. A rebinding attack drives the victim's browser, which does not attach
   a bearer token by itself (no cookies or session are used), so the attacker reaches only the 401.
2. An `Origin` check in front of `/mcp`: a request with an `Origin` not on `reconcile.mcp.allowed-origins` (empty by
   default) gets 403 before security or the transport. Non-browser MCP clients send no `Origin`.
3. Read-only tools, tenant-scoped, rate-limited, audited: the worst case of a stolen token is a rate-limited read of
   one tenant, which the token already allows through the REST API.

The `.trivyignore` entry stays (expiry unchanged) with this reasoning. Remove it when Spring AI ships on mcp-core 1.x.
The related wildcard-CORS finding in the same SDK (CVE-2026-34237) is covered by the same Origin check.

### Dependency side effect

mcp-json-jackson2 0.18.3 validates tool input with `com.networknt:json-schema-validator` 2.x. The project pinned
1.5.1 in test scope for its contract tests, which won the version conflict and removed the library from the packaged
app (the server failed to start). The pin is now 2.0.0, compile scope, and the four contract-test helpers use the 2.x API.

## 4. Consequences

- A second API surface over the same data, with the same identity model; nothing new to trust.
- Per-JVM rate limit: with N replicas the tenant limit is N times the setting. The Redis bucket used for investigations
  can be reused if that matters.
- Results are plain text (the agent's formatting), not structured JSON. Good enough for an assistant; a typed output
  schema is the upgrade path.
