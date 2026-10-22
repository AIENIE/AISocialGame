import type { ThinkingMode, ThinkingEffort } from "./aiModelConfig";

export interface LabSelection { modelKey: string; thinkingMode: ThinkingMode; thinkingEffort: ThinkingEffort | null }
export interface LabAgent { id: string; name: string; personaId: string; modelSelection: LabSelection; version: number }
export interface LabCost { spentPoints: number; heldPoints: number; pendingPoints: number; pendingCalls: number; totalCalls: number; actualCost: number | string }
export interface LabExperienceHead { experienceId: string; experience: string; sequenceNumber: number }
export interface LabFrozenAgent { agentId: string; participantId: string; name: string; personaId: string; persona: Record<string, unknown>; modelSelection: LabSelection }
export interface LabConfiguration {
  playerCount: number; targetGames: number; wordPack: string; hasBlank: boolean;
  roster: LabFrozenAgent[]; coach: LabSelection; experiences: Record<string, LabExperienceHead>;
  initialExperiences: Record<string, LabExperienceHead>; trainingCreated?: number;
}
export interface LabResult { actorId: string; agentId: string; participantId: string; name: string; ai: boolean; role: string; won: boolean; successfulActions: number; failedActions: number }
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
  id: string; agentId: string; participantId: string; status: string; pairs: number; completedGames: number; validPairs: number;
  baseline: LabEvaluationStats; candidate: LabEvaluationStats; winRateDifference: number;
}
export interface LabExperiment {
  latestFailureMatchId?: string; completedGames?: number; reviewedGames?: number; abortedGames?: number;
  id: string; name: string; status: string; reason?: string; budgetPoints: number; createdAt: string;
  attemptedGames: number; reviewedThrough: number; playerCount: number; targetGames: number;
  wordPack: string; hasBlank: boolean; cost: LabCost; activeMatches?: LabMatch[]; evaluations?: LabEvaluation[];
  enrollment?: { status?: string; matchId?: string; confirmUntil?: number }; configuration?: LabConfiguration; reviewFailed?: boolean;
}
export interface LabExperience {
  id: string; experimentId: string; agentId: string; participantId: string; sequenceNumber: number; summary: string; previousId: string;
  evidence: { matchId?: string; archiveId?: string; eventIds?: string[]; requestId?: string; rollbackOf?: string }; createdAt: string;
}
export interface LabPage<T> { items: T[]; total: number; page: number }
export interface CreateLabAgent { name: string; personaId: string; selection: LabSelection }
export interface LabParticipantInput { agentId: string; initialExperienceId?: string; selection?: LabSelection }
export interface CreateLabExperiment {
  name: string; participants: LabParticipantInput[]; playerCount: number; targetGames: number; wordPack: string;
  hasBlank: boolean; budgetPoints: number; coach: LabSelection;
}
export interface LabObservation { match: LabMatch; room: { id: string; name: string; roomCode: string }; state?: import("./index").GameState }
export interface LabDiagnostics {
  matchId: string; sequenceNumber: number; status: string; archiveId?: string;
  failure?: { code:string; stage:string; actorName?:string; round?:number; phase?:string; modelKey?:string; jobId?:string; inferred?:boolean; action?:{type:string;content:string} };
  tasks: { jobId:string; status:string; reason:string; actorName:string; modelKey:string; kind:string; attempts:{attempt:number;flags?:string[];failureStage?:string;settlementState?:string;requestId?:string}[] }[];
  calls: { requestId:string;state:string;billedPoints:number;heldPoints:number;purpose:string }[];
}
