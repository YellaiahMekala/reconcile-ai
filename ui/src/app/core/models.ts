// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
/** Wire types. Amounts arrive as JSON strings or numbers from BigDecimal; they are displayed, never computed on. */
export type Amount = string | number;

export interface Persona {
  id: string;
  subject: string;
  tenantId: string;
  roles: string[];
  label: string;
}

export interface Session {
  token: string;
  /** Present for identity-provider sign-in; used only as id_token_hint on logout. */
  idToken?: string;
  persona: Persona;
  expiresAt: string;
}

export interface AuthConfig {
  mode: 'demo' | 'oidc';
  issuer: string | null;
  clientId: string | null;
}

export interface BreakItem {
  id: string;
  breakReference: string;
  status: string;
  ledgerAmount: Amount;
  externalAmount: Amount;
  variance: Amount;
  currency: string;
  source: string;
  version: number;
  createdAt: string;
}

export interface Evidence {
  id: string;
  breakItemId: string;
  fileName: string;
  contentType: string;
  contentHash: string;
  sizeBytes: number;
  status: string;
  deduplicated: boolean;
  chunkCount: number | null;
  suspectChunkCount: number | null;
  failureReason: string | null;
  createdAt: string;
}

export interface SearchHit {
  chunkId: string;
  evidenceId: string;
  fileName: string;
  chunkIndex: number;
  vectorScore: number | null;
  keywordRank: number | null;
  fusedScore: number;
  snippet: string;
}

export interface InvestigationJob {
  id: string;
  breakItemId: string;
  mode: string;
  status: string;
  stage: string;
  initiatedBy: string;
  contextTokenEstimate: number | null;
  failureReason: string | null;
  proposalId: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  reviewVerdict: string | null;
  riskFlags: string[] | null;
  /** True when a gateway call's result was never confirmed; re-runs stay blocked until it is reconciled. */
  outcomeUnknown: boolean;
  reconciledAt: string | null;
  reconciledBy: string | null;
  reconcileRef: string | null;
  ledgerExecutionCertainty: string | null;
  ledgerStatus: string | null;
  /** EKA procedure lookup: null when knowledge is off. */
  knowledgeOutcome: KnowledgeOutcome | null;
  knowledgeReason: string | null;
  /** "kb:<uuid>" procedure ids: consulted for reference, never evidence. */
  knowledgeRefs: string[] | null;
}

export type KnowledgeOutcome = 'OK' | 'EMPTY' | 'UNAVAILABLE' | 'REJECTED';

/** One gateway call of a job plus the gateway ledger's view of it. No prompt or completion text. */
export interface GatewayCall {
  attempt: number;
  idempotencyKey: string;
  outcome: string;
  httpStatus: number;
  gatewayCode: string | null;
  invocationId: string | null;
  gatewayStatus: string | null;
  inputTokens: number;
  outputTokens: number;
  latencyMs: number;
  createdAt: string;
  /** FOUND, NOT_FOUND, UNAVAILABLE or NOT_CHECKED. */
  ledgerResult: string;
  ledgerStatus: string | null;
  ledgerExecutionCertainty: string | null;
  ledgerModelKey: string | null;
  ledgerProvider: string | null;
  ledgerInputTokens: number | null;
  ledgerOutputTokens: number | null;
  ledgerUsageSource: string | null;
  ledgerCostUsd: Amount | null;
}

/** One tool call an agent made. Arguments are never exposed, only the tool, outcome and size. */
export interface ToolCall {
  seq: number;
  tool: string;
  outcome: string;
  resultChars: number;
  latencyMs: number;
}

export interface ProgressEvent {
  jobId: string;
  breakItemId: string;
  status: string;
  stage: string;
  proposalId: string | null;
  failureReason: string | null;
  terminal: boolean;
  at: string;
}

export interface Proposal {
  id: string;
  breakItemId: string;
  jobId: string;
  rootCause: string;
  explanation: string;
  recommendedAction: string;
  confidence: Amount;
  citedChunkIds: string[];
  citationsValid: boolean;
  invalidCitations: string | null;
  route: string;
  routeReason: string;
  status: string;
  makerSubject: string;
  varianceAmount: Amount;
  currency: string;
  createdAt: string;
}

export interface ApprovalTask {
  id: string;
  proposalId: string;
  breakItemId: string;
  requiredRole: string;
  status: string;
  makerSubject: string;
  decidedBy: string | null;
  decisionComment: string | null;
  decidedAt: string | null;
  createdAt: string;
}

export interface AuditEvent {
  sequence: number;
  eventType: string;
  aggregateType: string;
  aggregateId: string;
  actor: string;
  details: string;
  occurredAt: string;
  prevHash: string;
  hash: string;
}

export interface ChainVerification {
  valid: boolean;
  eventsChecked: number;
  firstBrokenSequence: number | null;
  headHash: string;
}

export interface ApiError {
  code: string;
  message: string;
}
