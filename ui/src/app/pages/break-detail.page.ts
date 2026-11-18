import { Component, OnDestroy, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription, interval, switchMap, takeWhile } from 'rxjs';
import { ApiService, errorText } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { isReconciled, knowledgeView, RECONCILER_ROLES, validReference } from '../core/knowledge';
import {
  AuditEvent,
  BreakItem,
  Evidence,
  GatewayCall,
  InvestigationJob,
  ProgressEvent,
  Proposal,
  SearchHit,
  ToolCall,
} from '../core/models';
import { StatusBadge } from './status-badge';

const STAGES = ['QUEUED', 'RETRIEVING', 'CALLING_MODEL', 'VERIFYING', 'ROUTING', 'COMPLETED'];

/**
 * One break: evidence (upload, status, hybrid search), investigation with live SSE progress, cited proposals and
 * the audit trail. Progress is lifecycle metadata only; the proposal is fetched over REST once it is verified.
 */
@Component({
  selector: 'app-break-detail',
  imports: [FormsModule, StatusBadge],
  template: `
    @if (item(); as b) {
      <h1>{{ b.breakReference }} <app-status [value]="b.status" /></h1>
      <p class="muted">
        Ledger {{ b.ledgerAmount }} · External {{ b.externalAmount }} · Variance <strong>{{ b.variance }} {{ b.currency }}</strong>
      </p>
    }
    @if (error()) {
      <p class="error">{{ error() }}</p>
    }

    <div class="grid">
      <section class="card">
        <h2>Evidence</h2>
        @if (auth.hasRole('RECON_ANALYST')) {
          <label class="upload">
            Upload statement / confirmation (txt, csv, pdf)
            <input type="file" (change)="upload($event)" />
          </label>
        }
        <ul class="plain">
          @for (e of evidence(); track e.id) {
            <li>
              <app-status [value]="e.status" /> {{ e.fileName }}
              <span class="muted">{{ e.chunkCount ?? 0 }} chunks
                @if (e.suspectChunkCount) { · {{ e.suspectChunkCount }} quarantined }
                @if (e.failureReason) { · {{ e.failureReason }} }
              </span>
            </li>
          } @empty {
            <li class="muted">No evidence uploaded.</li>
          }
        </ul>
        @if (!auth.hasRole('SRE_ADMIN')) {
          <form class="row" (ngSubmit)="search()">
            <input name="q" [(ngModel)]="query" placeholder="Hybrid search, e.g. wire fee" />
            <button type="submit" [disabled]="!query">Search</button>
          </form>
          @for (h of hits(); track h.chunkId) {
            <div class="hit">
              <div class="muted">{{ h.fileName }} #{{ h.chunkIndex }} · fused {{ h.fusedScore.toFixed(4) }}
                · vector {{ h.vectorScore?.toFixed(3) ?? '–' }} · keyword rank {{ h.keywordRank ?? '–' }}</div>
              <div>{{ h.snippet }}</div>
            </div>
          }
        }
      </section>

      <section class="card">
        <h2>Investigation</h2>
        @if (auth.hasRole('RECON_ANALYST')) {
          <form class="col" (ngSubmit)="investigate()">
            <textarea name="note" [(ngModel)]="note" maxlength="1000" rows="2"
              placeholder="Optional analyst note (scanned for prompt injection)"></textarea>
            <label class="muted">
              <input type="checkbox" name="agent" [(ngModel)]="agentMode" />
              Agent mode: the model chooses read-only tools to look at the evidence
            </label>
            <button type="submit" [disabled]="running()">Run AI investigation</button>
          </form>
        }
        @if (progress(); as p) {
          <ol class="stages">
            @for (s of stages; track s) {
              <li [class.done]="stageIndex(p.stage) > $index || (p.terminal && p.status === 'SUCCEEDED')"
                  [class.active]="!p.terminal && stageIndex(p.stage) === $index">{{ s }}</li>
            }
          </ol>
          <p><app-status [value]="p.status" />
            @if (p.failureReason) { <span class="error">{{ p.failureReason }}</span> }
          </p>
        }

        @if (job(); as j) {
          @if (knowledge(); as k) {
            <p class="knowledge">
              <strong>Knowledge</strong> <app-status [value]="k.outcome" />
              @if (k.degraded) { <span class="warn-text">ran with degraded context</span> }
              @if (k.reason) { <span class="muted">{{ k.reason }}</span> }
              @if (k.consultedText) { <br /><span class="muted">{{ k.consultedText }}</span> }
            </p>
          }
          @if (j.outcomeUnknown) {
            <p class="warn-text">
              The gateway outcome for this investigation is unknown. A re-run is blocked until it is reconciled.
            </p>
          }
          @if (canReconcile() && j.outcomeUnknown) {
            <form class="row" (ngSubmit)="reconcile()">
              <input name="reconcileRef" [(ngModel)]="reconcileRef" maxlength="64"
                placeholder="Gateway invocation id or ticket reference" />
              <button type="submit" [disabled]="!validRef() || reconciling()">Reconcile</button>
            </form>
            @if (reconcileError()) { <p class="error">{{ reconcileError() }}</p> }
          }
          @if (reconciled()) {
            <p class="muted">
              Reconciled by {{ j.reconciledBy }} at {{ j.reconciledAt }} � ref {{ j.reconcileRef }}
              @if (j.ledgerStatus || j.ledgerExecutionCertainty) {
                � ledger {{ j.ledgerStatus ?? '�' }} / {{ j.ledgerExecutionCertainty ?? '�' }}
              }
            </p>
          }
          @if (canReconcile()) {
            <details class="gateway-calls" (toggle)="toggleCalls($event)">
              <summary>Gateway calls</summary>
              @if (callsError()) { <p class="error">{{ callsError() }}</p> }
              <table>
                <thead><tr><th>#</th><th>Outcome</th><th>HTTP</th><th>Invocation</th><th>Latency</th>
                  <th>Tokens in/out</th><th>Ledger</th><th>Certainty</th><th>Cost USD</th></tr></thead>
                <tbody>
                  @for (c of gatewayCalls(); track c.attempt) {
                    <tr>
                      <td>{{ c.attempt }}</td><td><app-status [value]="c.outcome" /></td><td>{{ c.httpStatus }}</td>
                      <td class="mono">{{ c.invocationId ?? '�' }}</td><td>{{ c.latencyMs }} ms</td>
                      <td>{{ c.inputTokens }} / {{ c.outputTokens }}</td>
                      <td><app-status [value]="c.ledgerResult" /> {{ c.ledgerStatus ?? '' }}</td>
                      <td>{{ c.ledgerExecutionCertainty ?? '�' }}</td><td>{{ c.ledgerCostUsd ?? '�' }}</td>
                    </tr>
                  } @empty {
                    <tr><td colspan="9" class="muted">No gateway calls.</td></tr>
                  }
                </tbody>
              </table>
            </details>
          }
        }

        @if (toolCalls().length > 0) {
          <h3>What the agent looked at</h3>
          <ol class="toolcalls">
            @for (c of toolCalls(); track c.seq) {
              <li>{{ c.tool }} <app-status [value]="c.outcome" />
                <span class="muted">{{ c.resultChars }} chars · {{ c.latencyMs }} ms</span></li>
            }
          </ol>
        }

        @for (p of proposals(); track p.id) {
          <article class="proposal">
            <header>
              <strong>{{ p.rootCause }}</strong> · confidence {{ p.confidence }}
              · route <app-status [value]="p.route" /> <span class="muted">({{ p.routeReason }})</span>
              · <app-status [value]="p.status" />
            </header>
            <p>{{ p.explanation }}</p>
            <p><em>Recommended:</em> {{ p.recommendedAction }}</p>
            <p class="muted">
              Citations: <app-status [value]="p.citationsValid ? 'VALID' : 'INVALID'" />
              {{ p.citedChunkIds.length }} cited
              @if (p.invalidCitations) { · not in retrieval snapshot: {{ p.invalidCitations }} }
              · maker {{ p.makerSubject }}
            </p>
          </article>
        } @empty {
          <p class="muted">No proposals yet.</p>
        }
      </section>
    </div>

    <section class="card">
      <h2>Audit trail <span class="muted">(hash-chained, append-only)</span></h2>
      <table>
        <thead><tr><th>#</th><th>Event</th><th>Actor</th><th>When</th><th>Hash</th></tr></thead>
        <tbody>
          @for (a of audit(); track a.sequence) {
            <tr>
              <td>{{ a.sequence }}</td><td>{{ a.eventType }}</td><td>{{ a.actor }}</td>
              <td>{{ a.occurredAt.slice(0, 19).replace('T', ' ') }}</td>
              <td class="mono">{{ a.hash.slice(0, 12) }}…</td>
            </tr>
          } @empty {
            <tr><td colspan="5" class="muted">No events.</td></tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class BreakDetailPage implements OnInit, OnDestroy {
  readonly id = input.required<string>();
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);

  readonly stages = STAGES;
  readonly item = signal<BreakItem | null>(null);
  readonly evidence = signal<Evidence[]>([]);
  readonly hits = signal<SearchHit[]>([]);
  readonly proposals = signal<Proposal[]>([]);
  readonly audit = signal<AuditEvent[]>([]);
  readonly progress = signal<ProgressEvent | null>(null);
  readonly toolCalls = signal<ToolCall[]>([]);
  readonly job = signal<InvestigationJob | null>(null);
  readonly gatewayCalls = signal<GatewayCall[]>([]);
  readonly reconciling = signal(false);
  readonly reconcileError = signal<string | null>(null);
  readonly callsError = signal<string | null>(null);
  readonly knowledge = computed(() => {
    const j = this.job();
    return j ? knowledgeView(j) : null;
  });
  readonly reconciled = computed(() => {
    const j = this.job();
    return !!j && isReconciled(j);
  });
  readonly running = signal(false);
  readonly error = signal<string | null>(null);
  query = '';
  reconcileRef = '';
  note = '';
  agentMode = false;
  private subs = new Subscription();

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.subs.unsubscribe();
  }

  canReconcile(): boolean {
    return this.auth.hasRole(...RECONCILER_ROLES);
  }

  validRef(): string | null {
    return validReference(this.reconcileRef);
  }

  private loadJob(jobId: string): void {
    this.api.getJob(jobId).subscribe({ next: (j) => this.job.set(j), error: () => undefined });
  }

  reconcile(): void {
    const j = this.job();
    const ref = this.validRef();
    if (!j || !ref) return;
    this.reconcileError.set(null);
    this.reconciling.set(true);
    this.api.reconcileJob(j.id, ref).subscribe({
      next: (updated) => {
        this.reconciling.set(false);
        this.reconcileRef = '';
        this.job.set(updated);
        this.loadJob(updated.id);
        this.loadGatewayCalls(updated.id);
      },
      error: (e) => {
        this.reconciling.set(false);
        this.reconcileError.set(errorText(e));
      },
    });
  }

  toggleCalls(ev: Event): void {
    const j = this.job();
    if (j && (ev.target as HTMLDetailsElement).open) this.loadGatewayCalls(j.id);
  }

  private loadGatewayCalls(jobId: string): void {
    this.callsError.set(null);
    this.api.gatewayCalls(jobId).subscribe({
      next: (c) => this.gatewayCalls.set(c),
      error: (e) => this.callsError.set(errorText(e)),
    });
  }

  stageIndex(stage: string): number {
    return STAGES.indexOf(stage);
  }

  refresh(): void {
    const id = this.id();
    const fail = (e: unknown) => this.error.set(errorText(e));
    this.api.getBreak(id).subscribe({ next: (b) => this.item.set(b), error: fail });
    this.api.listEvidence(id).subscribe({ next: (e) => this.evidence.set(e), error: fail });
    this.api.proposalsForBreak(id).subscribe({ next: (p) => this.proposals.set(p), error: fail });
    this.api.auditFor(id).subscribe({ next: (a) => this.audit.set(a), error: () => this.audit.set([]) });
  }

  upload(ev: Event): void {
    const file = (ev.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.error.set(null);
    this.api.uploadEvidence(this.id(), file).subscribe({
      next: () => {
        this.refresh();
        // Ingestion is asynchronous: poll until no evidence is still processing.
        this.subs.add(
          interval(1000)
            .pipe(
              switchMap(() => this.api.listEvidence(this.id())),
              takeWhile((list) => list.some((e) => !['READY', 'FAILED', 'QUARANTINED'].includes(e.status)), true),
            )
            .subscribe((list) => {
              this.evidence.set(list);
              if (!list.some((e) => !['READY', 'FAILED', 'QUARANTINED'].includes(e.status))) this.refresh();
            }),
        );
      },
      error: (e) => this.error.set(errorText(e)),
    });
    (ev.target as HTMLInputElement).value = '';
  }

  search(): void {
    this.api.searchEvidence(this.id(), this.query).subscribe({
      next: (h) => this.hits.set(h),
      error: (e) => this.error.set(errorText(e)),
    });
  }

  private loadToolCalls(jobId: string): void {
    this.api.toolCalls(jobId).subscribe({ next: (c) => this.toolCalls.set(c), error: () => this.toolCalls.set([]) });
  }

  investigate(): void {
    this.error.set(null);
    this.running.set(true);
    this.toolCalls.set([]);
    this.job.set(null);
    this.api.startInvestigation(this.id(), this.note || undefined, this.agentMode ? 'AGENT' : undefined).subscribe({
      next: (job) => {
        this.job.set(job);
        this.progress.set({ jobId: job.id, breakItemId: job.breakItemId, status: job.status, stage: job.stage,
          proposalId: null, failureReason: null, terminal: false, at: job.createdAt });
        this.subs.add(
          this.api.jobEvents(job.id).subscribe({
            next: (p) => {
              this.progress.set(p);
              if (p.terminal) {
                this.running.set(false);
                this.refresh();
                this.loadJob(job.id);
                if (this.agentMode) this.loadToolCalls(job.id);
              }
            },
            // Stream dropped (proxy, timeout): fall back to one REST read of the job.
            error: () => this.api.getJob(job.id).subscribe((j) => {
              this.running.set(j.status === 'QUEUED' || j.status === 'RUNNING');
              this.job.set(j);
              this.refresh();
            }),
          }),
        );
      },
      error: (e) => {
        this.running.set(false);
        this.error.set(errorText(e));
      },
    });
  }
}
