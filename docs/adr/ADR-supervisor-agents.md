# ADR: supervisor with specialist agents (built, measured: decision rule not met)

Status: **implemented, tested offline and measured live (2026-11-13). The decision rule in section 5 was not met, so supervised modes stay off by default and single call stays the default.** Both variants, the reviewer gate rule, V11, 12 hard cases and the comparison harness exist. Live result on gpt-4o-mini (40 cases): no wrong cause reached an approver in any mode on the hard set (nothing to halve); cost per case was 7.9x (S1) and 12.3x (S2) single call against a 5x limit; S2 reached only 50% root cause on the hard set. Full write-up: `docs/proof/phase4g-supervised-eval.md`.

## 1. Question to answer

Does splitting the investigation into specialist agents under a supervisor give better results than the two modes that already exist, enough to pay for the extra model calls? The deliverable is a measured answer, including "no" if that is what the numbers say.

What already exists (Phase 4A/4B, merged):

| Mode | How it works | Live result (28 synthetic cases, gpt-4o-mini, see `docs/proof/phase4-openai-eval.md`) |
|---|---|---|
| `SINGLE_CALL` | one prepared prompt, one model call | root cause 96.4%, route 89.3%, p50 2.5 s, $0.00015/case |
| `AGENT` | one model chooses among 4 read-only tools (`ReconTools`) | root cause 100%, route 57.1% (mostly LOW_CONFIDENCE, fails safe), p50 9.5 s, $0.0012/case (8x) |

The earlier conclusion was that the single agent is not worth its cost on this data. A supervisor adds more calls, so it starts with a handicap. It needs a case where it can win, and the eval must contain that case.

## 2. Where a supervisor could plausibly help

Only on inputs where one pass is likely to be wrong in a way a second look can catch:

- evidence that conflicts (statement says timing difference, confirmation says fee);
- a misleading analyst note;
- several plausible causes with similar support;
- a draft whose cited chunk does not actually say what the explanation claims (the citation check only proves the id was retrieved, not that the text supports the claim).

The last one is the real gap in the current design and the strongest argument for a reviewer. On easy cases (the current 28) expect the supervisor to cost more and gain nothing.

## 3. Hard constraints (carried over, not negotiable)

1. The LLM proposes; code decides. No agent sets a route, approves, writes, or calls a mutating tool. The policy gate stays deterministic Java.
2. Tenant and break come from the job row via `AgentRunContext`; no tool or agent takes either as a parameter.
3. A chunk id is citable only if a search tool returned it during this run (existing rule).
4. All agents share one run-wide budget (tool calls, model turns, wall clock) so adding agents cannot multiply the limits.
5. Every model call goes through `AccountingChatModel` (retry, breaker, `llm_call`, token metrics). Injection scan and PII masking on every tool result (existing `ReconTools.guarded`).
6. Off by default; core runs without it.

## 4. Proposed design

New mode `InvestigationMode.SUPERVISED` (enum value plus the job column check). `InvestigationJobRunner` already branches on mode; add one branch that calls a new `SupervisedInvestigation` component and then runs the unchanged normalize, citation check, policy gate and approval flow.

```
analyst request (JWT) ──► job row (tenant, break, mode=SUPERVISED)
        │
        ▼
 SupervisedInvestigation (Java)   shares one AgentRunContext + budget
        │
        ├─► 1. EvidenceAnalyst   tools: getBreakSummary, listEvidence, searchEvidence
        │        output: findings[] {claim, chunk_id}, candidate root cause(s)
        │
        ├─► 2. RiskChecker       tools: findSimilarResolvedBreaks + deterministic facts
        │        (variance, currency, policy thresholds passed in as text)
        │        output: flags[] (e.g. "similar breaks resolved as TIMING", "non-USD"); advisory only
        │
        ├─► draft: the supervisor asks the analyst model for the final JSON ProposalDraft
        │
        └─► 3. Reviewer          no tools; receives draft + the exact text of each cited chunk
                 output: verdict AGREE | CHALLENGE, per-claim "supported / not supported"
```

### 4.1 Two supervisor variants (both evaluated)

| Variant | Control flow | Why include it |
|---|---|---|
| **S1 fixed pipeline** | Java calls analyst, then risk checker, then reviewer in a fixed order | predictable cost and latency; easiest to audit; the likely production choice |
| **S2 LLM supervisor** | a supervisor model decides which specialist to call next (LangChain4j supervisor / agents-as-tools pattern) | tests whether model-planned routing earns its extra calls; expected to be worse and costlier |

Report both. If S1 beats S2 at lower cost, that is itself the interview point: use the deterministic orchestrator unless the task needs dynamic planning.

### 4.2 How the reviewer affects routing without breaking constraint 1

The reviewer must return a verbatim quote from the cited chunk; a quote that is not a substring of that chunk (after whitespace normalisation) counts as `CHALLENGE`, so it cannot invent support. The reviewer never sets a route. Its output becomes one more input to `PolicyGateService`, as a new deterministic rule evaluated before the others:

- `CHALLENGE` (a cited claim not supported by its chunk) -> `MANUAL_REVIEW`, reason `REVIEWER_CHALLENGE`;
- `AGREE` -> no change; the existing rules (citations, unknown cause, confidence < 0.80, currency, variance) decide.

Risk-checker flags are shown to the human approver and recorded in the audit event; they do not change the route.

This keeps the property that a regulator can read the routing rules in Java, and it fails safe: the new component can only send more work to a human, never less.

### 4.3 Reuse (no new infrastructure)

| Need | Existing piece |
|---|---|
| run budget, trace, allowed ids | `AgentRunContext` (extend with an `agentName` per call) |
| model accounting, retry, breaker | `AccountingChatModel`, `LlmResilience` |
| guarded read-only tools | `ReconTools` / `ReconToolsFactory` (give the analyst all, the risk checker only `findSimilarResolvedBreaks`, the reviewer none) |
| audit | `AGENT_RUN_COMPLETED` event with trace digest; add per-agent entries to `agent_tool_call` via a new column in the next Flyway migration (after V9) |
| deterministic test model | `ScriptedAgentChatModel` (add scripted analyst, risk and reviewer scripts; fault marker `simulate reviewer challenge`) |
| evaluation | `EvalHarness`, `EvalRunner`, `EvalReportWriter`, `scripts/eval-compare.ps1` |

New code is small: `SupervisedInvestigation`, three prompt templates, one `ReviewerVerdict` record, one gate rule, one mode value, config `reconcile.agent.supervised.*`.

### 4.4 Agent framework choice

LangChain4j stays (already on the classpath, version 1.18.1, core libraries only, hand-built like `AgentConfig`). S1 needs only plain `ChatModel` calls and the existing tool classes; S2 uses the LangChain4j supervisor pattern. No Spring Boot starter is added, to keep the existing wiring.

## 5. Evaluation plan (fixed before any run)

1. Keep the 28 existing cases as the "easy" set.
2. Add about 12 hard cases, labeled before running: conflicting evidence (4), misleading analyst note (3), two plausible causes (3), explanation not supported by the cited chunk (2). The last two are the ones the citation check cannot catch.
3. Run SINGLE_CALL, AGENT, S1 and S2 on all 40, same model, temperature 0.
4. Metrics: root-cause accuracy, route accuracy, share of wrong-cause answers that end in MANUAL_REVIEW (safety), citation-supported rate (judged by reading the chunk, not just id membership), p50/p95 latency, cost per case, model calls per case.
5. Decision rule written now: the supervisor is "worth it" only if on the hard set it cuts wrong-cause answers that reach an approver by a clear margin (suggest at least half) and total cost stays below 5x single call. Otherwise, publish the negative result and leave it off.
6. Offline first: scripted models prove the plumbing and the gate rule at zero cost. The live run comes second (done 2026-11-13).

Expected cost of the live run: 40 cases x 4 modes x a few calls each on gpt-4o-mini is in the range of a few cents to well under a dollar, by extrapolation from the 4B run ($0.0012/case for the single agent). Confirm with you before any paid call.

Honest limits to state in the write-up: 40 author-written cases are not a benchmark; the reviewer is the same model family as the analyst, so correlated errors are possible (a different model for the reviewer is an option to test); results hold for the model and prompts used.

## 6. Tests to add (offline, no key)

- Supervisor off by default; request for SUPERVISED refused when disabled (mirror `AgentDisabledByDefaultIT`).
- Shared budget: three agents together cannot exceed `max-tool-calls` / `max-model-turns` (mirror `AgentSecurityIT` runaway test).
- Tenant isolation: no agent can reach another tenant's evidence.
- Reviewer `CHALLENGE` produces `MANUAL_REVIEW` / `REVIEWER_CHALLENGE`; `AGREE` leaves the route unchanged; a reviewer cannot upgrade a route.
- Injection text in evidence cannot make the reviewer return `AGREE` for a claim it cannot find in the cited chunk.
- Audit: one `AGENT_RUN_COMPLETED` event with per-agent trace digest; hash chain still verifies.

## 7. Effort and order

1. Gate rule + `ReviewerVerdict` + scripted models + offline tests (about a day).
2. S1 pipeline end to end under mock-llm (about a day).
3. Hard-case labeling and eval wiring (about a day).
4. S2 variant (half a day).
5. Live eval and the written comparison (half a day plus your go-ahead for spend).

## 8. Where this fits in the wider integration plan

- MCP: the four read-only tools are already isolated in `ReconTools`. A later step can expose them through Spring AI MCP to ai-gateway or EKA. That is separate from this ADR and needs its own authentication design, because MCP callers must still resolve to a tenant from a token, never from a tool argument.
- The incident-triage agent mentioned in planning belongs to the integration project as another consumer of the same pattern; it should not be added here.
- AWS live pieces (Bedrock eval, `envs/demo` deploy) are independent of this design and need your explicit go-ahead because they cost money.

## 9. Interview framing

"I already measured that a single tool-using agent was 8x the cost of a single call for the same accuracy, so I did not add agents by default. I identified the one failure the current checks cannot catch, a citation that exists but does not support the claim, and designed a reviewer for exactly that, wired in as a deterministic gate rule so it can only escalate to a human. I defined the pass/fail rule before running, and I will publish the result either way."

## 10. Implementation notes (added after the build)

- Mode names: `SUPERVISED` (S1, fixed Java order) and `SUPERVISED_PLANNED` (S2, supervisor model with the specialists as tools). Config `reconcile.agent.supervised.*`, off by default, also needs agent mode.
- The reviewer returns a verbatim quote per cited chunk; `ReviewerVerdict` makes the verdict in code (quote of 12+ characters found in the chunk after whitespace and case normalisation; skipped, unretrieved or unreadable answers are a challenge). Gate rule 0 `REVIEWER_CHALLENGE` can only send a break to manual review.
- S2 cannot skip the essentials: if the supervisor ends without a draft or without reviewing the final draft, Java runs the step and counts it as `plannerOverrides` in the audit event.
- S2 cannot undo a finished review either (added 2026-11-14, after the live run): once the current draft is reviewed and the reviewer agreed, every further delegation is refused with `ALREADY_REVIEWED` and the tool result tells the supervisor to reply DONE. Refusals are traced but do not count against `maxDelegations`.
- Persistence: V11 adds `agent_name` on `agent_tool_call`, plus `review_verdict` and `risk_flags` on `investigation_job`.
- Offline result: all jobs succeed in all four modes, no `AUTO_RESOLVE`, the easy-set floor holds. The scripted model is keyword rules, so its accuracy says nothing about real models. Model calls per case: 1.0, 3.0, 4.7 and 8.7 (single, agent, supervised, planned).
- Live result (run 3, shared budget 12/24/180): rule not met by either variant (see `docs/proof/phase4g-supervised-eval.md`). Decision: keep single call as the default, leave supervised off. The reviewer worked (it challenged the unsupported-citation case) but added manual work. The shared budget was first 8/14/120, too small for a real model (all failures `AGENT_TURN_LIMIT`); it was raised after seeing run 1 and both runs are kept.
- Why S2 often ended without a reviewed draft (found 2026-11-14 from the run-3 traces, no new paid run): in 10 of its 11 cases with a failed job or a wrong cause, the supervisor model ran analyst, risk checker and reviewer, got AGREE, and then started the analyst again although the prompt allows a re-run only after a challenge. The second analyst ran out of the shared budget, and its empty `UNKNOWN` draft replaced the reviewed one (or the run hit `AGENT_TURN_LIMIT`). The prompt rule is now enforced in code (previous bullet). Proven offline only; S2 has not been re-measured live, so its run-3 numbers still stand.
