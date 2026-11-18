import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { BreakDetailPage } from './break-detail.page';

describe('BreakDetailPage latest job on load', () => {
  let ctrl: HttpTestingController;

  function open(): { fixture: ReturnType<typeof TestBed.createComponent<BreakDetailPage>>; el: HTMLElement } {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    ctrl = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(BreakDetailPage);
    fixture.componentRef.setInput('id', 'b1');
    fixture.detectChanges();
    ctrl.expectOne('/api/v1/breaks/b1').flush({ id: 'b1', breakReference: 'BRK-1', status: 'OPEN' });
    ctrl.expectOne('/api/v1/breaks/b1/evidence').flush([]);
    ctrl.expectOne('/api/v1/breaks/b1/proposals').flush([]);
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  afterEach(() => ctrl.verify());

  it('shows the knowledge panel from the latest job after a reload', () => {
    const { fixture, el } = open();
    ctrl.match((r) => r.url.includes('audit')).forEach((r) => r.flush([]));
    ctrl.expectOne('/api/v1/breaks/b1/investigations/latest').flush({
      id: 'j1', breakItemId: 'b1', status: 'SUCCEEDED', stage: 'COMPLETED', outcomeUnknown: false,
      knowledgeOutcome: 'OK', knowledgeReason: null, knowledgeRefs: ['kb:a', 'kb:b'],
    });
    fixture.detectChanges();
    expect(el.querySelector('.knowledge')?.textContent).toContain('Procedures consulted: 2');
    expect(fixture.componentInstance.job()?.id).toBe('j1');
  });

  it('stays silent and shows no panel when the break has no job (404)', () => {
    const { fixture, el } = open();
    ctrl.match((r) => r.url.includes('audit')).forEach((r) => r.flush([]));
    ctrl.expectOne('/api/v1/breaks/b1/investigations/latest')
      .flush({ code: 'JOB_NOT_FOUND', message: 'none' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(el.querySelector('.knowledge')).toBeNull();
    expect(fixture.componentInstance.job()).toBeNull();
    expect(fixture.componentInstance.error()).toBeNull();
  });

  it('does not overwrite a job already set in this session', () => {
    const { fixture } = open();
    ctrl.match((r) => r.url.includes('audit')).forEach((r) => r.flush([]));
    fixture.componentInstance.job.set({ id: 'live' } as never);
    ctrl.expectOne('/api/v1/breaks/b1/investigations/latest').flush({ id: 'old' });
    expect(fixture.componentInstance.job()?.id).toBe('live');
  });
});
