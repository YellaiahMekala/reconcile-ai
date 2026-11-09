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
