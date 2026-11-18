import { InvestigationJob } from './models';
import { isReconciled, knowledgeTone, knowledgeView, validReference } from './knowledge';

type K = Pick<InvestigationJob, 'knowledgeOutcome' | 'knowledgeReason' | 'knowledgeRefs'>;
const job = (o: Partial<K>): K => ({ knowledgeOutcome: null, knowledgeReason: null, knowledgeRefs: null, ...o });

describe('knowledge view', () => {
  it('shows nothing when the outcome is null', () => {
    expect(knowledgeView(job({ knowledgeRefs: ['kb:1'] }))).toBeNull();
  });

  it('maps outcomes to tones', () => {
    expect(knowledgeTone('OK')).toBe('ok');
    expect(knowledgeTone('EMPTY')).toBe('neutral');
    expect(knowledgeTone('UNAVAILABLE')).toBe('warn');
    expect(knowledgeTone('REJECTED')).toBe('bad');
  });

  it('flags UNAVAILABLE as degraded context', () => {
    const v = knowledgeView(job({ knowledgeOutcome: 'UNAVAILABLE', knowledgeReason: 'timeout' }))!;
    expect(v.degraded).toBeTrue();
    expect(v.tone).toBe('warn');
    expect(v.reason).toBe('timeout');
    expect(v.consultedText).toBeNull();
  });

  it('counts consulted procedures', () => {
    const v = knowledgeView(job({ knowledgeOutcome: 'OK', knowledgeRefs: ['kb:a', 'kb:b'] }))!;
    expect(v.degraded).toBeFalse();
    expect(v.consultedText).toBe('Procedures consulted: 2 (reference only, not evidence)');
  });

  it('validates the reconcile reference', () => {
    expect(validReference('  inv-1 ')).toBe('inv-1');
    expect(validReference('   ')).toBeNull();
    expect(validReference('x'.repeat(65))).toBeNull();
  });

  it('detects a reconciled job', () => {
    expect(isReconciled({ reconciledAt: null, reconciledBy: null })).toBeFalse();
    expect(isReconciled({ reconciledAt: '2026-01-01T00:00:00Z', reconciledBy: 'u' })).toBeTrue();
  });
});
