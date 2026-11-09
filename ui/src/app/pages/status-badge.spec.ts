import { TestBed } from '@angular/core/testing';
import { StatusBadge } from './status-badge';

describe('StatusBadge', () => {
  function render(value: string): HTMLElement {
    const f = TestBed.createComponent(StatusBadge);
    f.componentRef.setInput('value', value);
    f.detectChanges();
    return f.nativeElement.querySelector('span');
  }

  it('maps statuses to tones', () => {
    expect(render('RESOLVED').className).toContain('ok');
    expect(render('FAILED_RETRYABLE').className).toContain('bad');
    expect(render('PENDING_L1').className).toContain('warn');
    expect(render('INVALID').className).toContain('bad');
    expect(render('OPEN').className).toContain('neutral');
    // agent tool-call outcomes
    expect(render('OK').className).toContain('ok');
    expect(render('DENIED').className).toContain('bad');
    expect(render('INJECTION_FILTERED').className).toContain('warn');
  });
});
