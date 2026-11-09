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
});
