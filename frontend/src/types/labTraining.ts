export type LabTrainingStatus = "DRAFT" | "RUNNING" | "PAUSED" | "COMPLETED" | "STOPPED";
export type LabTrainingStage = "PRACTICE" | "MERGE" | "REHEARSAL" | "HOLDOUT" | "GAMES" | "REPORT";
export type LabTrainingControl = "start" | "start-one" | "pause" | "resume" | "stop";
export interface LabTrainingSummary {
  id: string; operationId: string; status: LabTrainingStatus; stage: LabTrainingStage;
  goal: "RULES_EVIDENCE"; candidateStrategyVersionId?: string | null;
}
export interface CreateLabTrainingRun { operationId: string; goal: "RULES_EVIDENCE" }
export interface LabTrainingCall {
  requestId: string; state: string; billedPoints?: number | null; billedCost?: number | string | null;
  usage?: Record<string, unknown>; timing?: { firstTextMs?: number | null; firstEventMs?: number | null; totalMs?: number | null };
  executionState?: string; executionEndedAtEpochMs?: number;
}
export interface LabTrainingChange {
  operation: "ADD" | "UPDATE" | "RETIRE"; cardId?: string; evidenceIds: string[]; reason: string;
  card?: { id: string; role: string; phases: string[]; condition: string; advice: string; exceptions: string; priority?: number; actionKinds?: string[] };
}
export interface LabTrainingTask {
  id: string; caseId: string; kind: "PRACTICE" | "TEACH" | "REHEARSAL" | "HOLDOUT" | "JUDGE";
  variant?: string; status: "PENDING" | "RUNNING" | "WAITING" | "SUCCEEDED" | "FAILED" | "BLOCKED" | "SKIPPED";
  reason?: string | null; requestId?: string | null; attempt: number; paidRetries: number;
  startedAtEpochMs?: number | null; updatedAtEpochMs?: number; notBeforeEpochMs?: number;
  result?: {
    action?: Record<string, unknown>; speech?: string; evidenceEventIds?: string[];
    diagnosis?: { category: string; summary: string; evidenceIds: string[] };
    changes?: LabTrainingChange[];
    assessments?: { label: string; facts: string; evidence: string; response: string; targetErrors: number; ruleError: boolean; evidenceLeak: boolean; evidenceIds: string[]; reason: string }[];
    variantByLabel?: Record<string, string>; [key: string]: unknown;
  };
  diagnostics?: { calls?: LabTrainingCall[]; inputCodePoints?: number; failure?: unknown; [key: string]: unknown };
}
export interface LabTrainingRun extends LabTrainingSummary {
  experimentId: string; reason?: string | null; configuration: Record<string, unknown>;
  progress: { totalTasks: number; completedTasks: number; failedTasks: number; developmentCases: number; holdoutPairs: number; completedHoldoutPairs: number; gamePairs: number; completedGamePairs: number };
  cost: { spentPoints: number; externalPoints: number; totalKnownPoints: number; budgetPoints: number; pendingCalls: number; totalCalls: number; actualCost: number | string };
  tasks: LabTrainingTask[];
  assessment: { status?: "NOT_RUN" | "INCOMPLETE" | "NO_IMPROVEMENT" | "REGRESSION" | "IMPROVED"; baselineErrors?: number; candidateErrors?: number; improvedSourceGroups?: number | string[]; newRuleErrors?: number; newEvidenceLeaks?: number; conflicts?: unknown[]; [key: string]: unknown };
  nextStep?: string;
}
export interface LabTrainingCases {
  items: { id: string; purpose: string; sourceType: string; sourceGroup: string; personaId: string; participantId: string; contentHash: string; data: Record<string, unknown> }[];
  coverage: Record<string, unknown>;
}
