import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ApiService } from './api.service';

describe('ApiService investigations', () => {
  let api: ApiService;
  let ctrl: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApiService);
    ctrl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => ctrl.verify());

  it('keeps the single-call request body unchanged when no mode is chosen', () => {
    api.startInvestigation('b1', 'check the fee').subscribe();
    const req = ctrl.expectOne('/api/v1/breaks/b1/investigations');
    expect(req.request.body).toEqual({ analystNote: 'check the fee' });
    req.flush({});
  });

  it('sends mode AGENT only when asked for', () => {
    api.startInvestigation('b1', undefined, 'AGENT').subscribe();
    const req = ctrl.expectOne('/api/v1/breaks/b1/investigations');
    expect(req.request.body).toEqual({ mode: 'AGENT' });
    req.flush({});
  });

  it('reads the tool-call timeline of a job', () => {
    let calls: unknown[] = [];
    api.toolCalls('j1').subscribe((c) => (calls = c));
    ctrl.expectOne('/api/v1/investigations/j1/tool-calls').flush([
      { seq: 1, tool: 'getBreakSummary', outcome: 'OK', resultChars: 90, latencyMs: 4 },
    ]);
    expect(calls.length).toBe(1);
  });

  it('posts the reconcile reference and returns the refreshed job', () => {
    let ref = '';
    api.reconcileJob('j1', 'inv-42').subscribe((j) => (ref = j.reconcileRef ?? ''));
    const req = ctrl.expectOne('/api/v1/investigations/j1/reconcile');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reference: 'inv-42' });
    req.flush({ id: 'j1', outcomeUnknown: false, reconcileRef: 'inv-42' });
    expect(ref).toBe('inv-42');
  });

  it('surfaces a 409 from reconcile as an error', () => {
    let status = 0;
    api.reconcileJob('j1', 'x').subscribe({ error: (e) => (status = e.status) });
    ctrl.expectOne('/api/v1/investigations/j1/reconcile')
      .flush({ code: 'CONFLICT', message: 'not blocked' }, { status: 409, statusText: 'Conflict' });
    expect(status).toBe(409);
  });

  it('reads the gateway calls of a job', () => {
    let calls: unknown[] = [];
    api.gatewayCalls('j1').subscribe((c) => (calls = c));
    const req = ctrl.expectOne('/api/v1/investigations/j1/gateway-calls');
    expect(req.request.method).toBe('GET');
    req.flush([{ attempt: 1, outcome: 'TIMEOUT', ledgerResult: 'NOT_CHECKED' }]);
    expect(calls.length).toBe(1);
  });

  it('reads the latest job of a break', () => {
    let id = '';
    api.latestJob('b1').subscribe((j) => (id = j.id));
    const req = ctrl.expectOne('/api/v1/breaks/b1/investigations/latest');
    expect(req.request.method).toBe('GET');
    req.flush({ id: 'j9' });
    expect(id).toBe('j9');
  });

  it('surfaces a 404 from the latest-job read as an error the caller can ignore', () => {
    let status = 0;
    api.latestJob('b1').subscribe({ error: (e) => (status = e.status) });
    ctrl.expectOne('/api/v1/breaks/b1/investigations/latest')
      .flush({ code: 'JOB_NOT_FOUND', message: 'none' }, { status: 404, statusText: 'Not Found' });
    expect(status).toBe(404);
  });
});
