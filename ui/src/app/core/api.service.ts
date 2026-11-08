import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, filter, map } from 'rxjs';
import { AuthService } from './auth.service';
import {
  ApprovalTask,
  AuditEvent,
  BreakItem,
  ChainVerification,
  Evidence,
  InvestigationJob,
  ProgressEvent,
  Proposal,
  SearchHit,
} from './models';
import { sseStream } from './sse';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  listBreaks(): Observable<BreakItem[]> {
    return this.http.get<BreakItem[]>('/api/v1/breaks', { params: { size: 50 } });
  }

  getBreak(id: string): Observable<BreakItem> {
    return this.http.get<BreakItem>(`/api/v1/breaks/${id}`);
  }

  createBreak(body: {
    breakReference: string;
    ledgerAmount: string;
    externalAmount: string;
    currency: string;
  }): Observable<BreakItem> {
    return this.http.post<BreakItem>('/api/v1/breaks', body);
  }

  listEvidence(breakId: string): Observable<Evidence[]> {
    return this.http.get<Evidence[]>(`/api/v1/breaks/${breakId}/evidence`);
  }

  uploadEvidence(breakId: string, file: File): Observable<Evidence> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<Evidence>(`/api/v1/breaks/${breakId}/evidence`, form);
  }

  searchEvidence(breakId: string, q: string): Observable<SearchHit[]> {
    return this.http.get<SearchHit[]>(`/api/v1/breaks/${breakId}/evidence/search`, { params: { q } });
  }

  startInvestigation(breakId: string, analystNote?: string): Observable<InvestigationJob> {
    return this.http.post<InvestigationJob>(
      `/api/v1/breaks/${breakId}/investigations`,
      analystNote ? { analystNote } : {},
    );
  }

  getJob(jobId: string): Observable<InvestigationJob> {
    return this.http.get<InvestigationJob>(`/api/v1/investigations/${jobId}`);
  }

  jobEvents(jobId: string): Observable<ProgressEvent> {
    return sseStream(`/api/v1/investigations/${jobId}/events`, this.auth.token() ?? '').pipe(
      filter((m) => m.event === 'progress'),
      map((m) => JSON.parse(m.data) as ProgressEvent),
    );
  }

  proposalsForBreak(breakId: string): Observable<Proposal[]> {
    return this.http.get<Proposal[]>(`/api/v1/breaks/${breakId}/proposals`);
  }

  approvals(): Observable<ApprovalTask[]> {
    return this.http.get<ApprovalTask[]>('/api/v1/approvals');
  }

  decide(taskId: string, decision: 'APPROVE' | 'REJECT', comment: string): Observable<ApprovalTask> {
    return this.http.post<ApprovalTask>(`/api/v1/approvals/${taskId}/decision`, { decision, comment });
  }

  auditFor(aggregateId: string): Observable<AuditEvent[]> {
    return this.http.get<AuditEvent[]>(`/api/v1/audit/aggregates/${aggregateId}`);
  }

  verifyAudit(): Observable<ChainVerification> {
    return this.http.get<ChainVerification>('/api/v1/audit/verify');
  }
}

/** Human-readable error: the API's {code, message} body when present, otherwise the HTTP status. */
export function errorText(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    const body = e.error as { code?: string; message?: string } | null;
    if (body && (body.code || body.message)) {
      return [body.code, body.message].filter(Boolean).join(': ');
    }
    if (e.status === 403) return 'FORBIDDEN: your role cannot perform this action';
    if (e.status === 404) return 'NOT_FOUND (or it belongs to another tenant)';
    return `HTTP ${e.status}`;
  }
  return String(e);
}
