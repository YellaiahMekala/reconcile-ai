import { InvestigationJob, KnowledgeOutcome } from './models';

/** Badge tone for a knowledge outcome; mirrors the status badge palette. */
export function knowledgeTone(outcome: KnowledgeOutcome): 'ok' | 'warn' | 'bad' | 'neutral' {
  switch (outcome) {
    case 'OK':
      return 'ok';
    case 'UNAVAILABLE':
      return 'warn';
    case 'REJECTED':
      return 'bad';
    default:
      return 'neutral';
  }
}

/** Everything the Knowledge line shows. */
export interface KnowledgeView {
  outcome: KnowledgeOutcome;
  tone: 'ok' | 'warn' | 'bad' | 'neutral';
  reason: string | null;
  degraded: boolean;
  /** Null when no procedures were consulted. */
  consultedText: string | null;
}

/** Null when the job has no knowledge outcome (knowledge is off), so the line is not rendered. */
export function knowledgeView(
  job: Pick<InvestigationJob, 'knowledgeOutcome' | 'knowledgeReason' | 'knowledgeRefs'>,
): KnowledgeView | null {
  if (!job.knowledgeOutcome) return null;
  const n = job.knowledgeRefs?.length ?? 0;
  return {
    outcome: job.knowledgeOutcome,
    tone: knowledgeTone(job.knowledgeOutcome),
    reason: job.knowledgeReason || null,
    degraded: job.knowledgeOutcome === 'UNAVAILABLE',
    consultedText: n > 0 ? `Procedures consulted: ${n} (reference only, not evidence)` : null,
  };
}

/** Roles allowed to reconcile a job and read its gateway calls. */
export const RECONCILER_ROLES = ['APPROVER_L2', 'SRE_ADMIN'];

export function isReconciled(job: Pick<InvestigationJob, 'reconciledAt' | 'reconciledBy'>): boolean {
  return !!job.reconciledAt || !!job.reconciledBy;
}

/** The trimmed reference to send, or null when blank or over the server's 64-character limit. */
export function validReference(ref: string): string | null {
  const t = ref.trim();
  return t.length > 0 && t.length <= 64 ? t : null;
}
