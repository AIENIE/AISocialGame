import type { ThinkingMode, ThinkingEffort } from "./aiModelConfig";

export interface LabSelection { modelKey: string; thinkingMode: ThinkingMode; thinkingEffort: ThinkingEffort | null }
export interface LabAgent { id: string; name: string; personaId: string; modelSelection: LabSelection; version: number }
export interface LabCost { spentPoints: number; heldPoints: number; pendingPoints: number; pendingCalls: number; totalCalls: number; actualCost: number | string }
export interface LabExperienceHead { experienceId: string; experience: string; sequenceNumber: number }
export interface LabFrozenAgent { agentId: string; name: string; personaId: string; persona: Record<string, unknown>; modelSelection: LabSelection }
export interface LabConfiguration {
  playerCount: number; targetGames: number; concurrency: number; wordPack: string; hasBlank: boolean;
  roster: LabFrozenAgent[]; coach: LabSelection; experiences: Record<string, LabExperienceHead>;
  initialExperiences: Record<string, LabExperienceHead>; trainingCreated?: number;
}
export interface LabResult { actorId: string; agentId: string; name: string; ai: boolean; role: string; won: boolean; successfulActions: number; failedActions: number }
export interface LabMatch {
  id: string; sequenceNumber: number; roomId: string; archiveId?: string; status: string; reason?: string;
  startsAtEpochMs: number; createdAt: string; variant?: string; results?: LabResult[];
  snapshot?: Record<string, unknown>;
}
export interface LabEvaluationStats {
  games: number; wins: number; winRate: number; validActionRate: number; failureRate: number; attemptedGames: number; failedGames: number;
  roles: Record<string, { games: number; wins: number; winRate: number }>;
}
export interface LabEvaluation {
  id: string; agentId: string; status: string; pairs: number; completedGames: number; validPairs: number;
  baseline: LabEvaluationStats; candidate: LabEvaluationStats; winRateDifference: number;
}
export interface LabExperiment {
  id: string; name: string; status: string; reason?: string; budgetPoints: number; createdAt: string;
  attemptedGames: number; reviewedThrough: number; playerCount: number; targetGames: number; concurrency: number;
  wordPack: string; hasBlank: boolean; cost: LabCost; activeMatches?: LabMatch[]; evaluations?: LabEvaluation[];
  enrollment?: { status?: string; matchId?: string; confirmUntil?: number }; configuration?: LabConfiguration; reviewFailed?: boolean;
}
export interface LabExperience {
  id: string; experimentId: string; agentId: string; sequenceNumber: number; summary: string; previousId: string;
  evidence: { matchId?: string; archiveId?: string; eventIds?: string[]; requestId?: string; rollbackOf?: string }; createdAt: string;
}
export interface LabPage<T> { items: T[]; total: number; page: number }
export interface CreateLabAgent { name: string; personaId: string; selection: LabSelection }
export interface CreateLabExperiment {
  name: string; agentIds: string[]; playerCount: number; targetGames: number; concurrency: number; wordPack: string;
  hasBlank: boolean; budgetPoints: number; coach: LabSelection; initialExperienceIds: Record<string, string>;
}
