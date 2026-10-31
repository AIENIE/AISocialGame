import type { ThinkingMode, ThinkingEffort } from "./aiModelConfig";

export interface LabSelection { modelKey: string; thinkingMode: ThinkingMode; thinkingEffort: ThinkingEffort | null }
export type LabGameId = "undercover" | "werewolf";
export interface LabWerewolfRules { template: "standard"; playerCount: 6 | 9; witchRule: "first_night"; winCondition: "side"; speechTime: number; hasLastWords: "first_night" }
export interface LabAgent { id: string; name: string; personaId: string; modelSelection: LabSelection; version: number }
export interface LabCost { spentPoints: number; pendingCalls: number; totalCalls: number; actualCost: number | string }
export interface LabExperienceHead { experienceId: string; experience: string; sequenceNumber: number }
export interface LabFrozenAgent { agentId: string; participantId: string; name: string; personaId: string; persona: Record<string, unknown>; modelSelection: LabSelection }
export interface LabConfiguration {
  gameId?: LabGameId; learningMode?: "INDIVIDUAL" | "SHARED_STRATEGY"; rules?: LabWerewolfRules; initialStrategyVersionId?: string; strategyHeadId?: string;
  scenarioRuns?: Record<string, LabScenarioRun>; activeScenario?: string;
  scenarioManifest?: { caseId: string; sourceType?: string; sourceGroup?: string; personaId?: string; role?: string }[];
  playerCount: number; targetGames: number; wordPack: string; hasBlank: boolean;
  roster: LabFrozenAgent[]; coach: LabSelection; experiences: Record<string, LabExperienceHead>;
  initialExperiences: Record<string, LabExperienceHead>; trainingCreated?: number;
}
export interface LabResult { actorId: string; agentId: string; participantId: string; name: string; ai: boolean; role: string; won: boolean; successfulActions: number; failedActions: number }
export interface LabMatch {
  gameId?: LabGameId;
  id: string; sequenceNumber: number; roomId: string; archiveId?: string; status: string; reason?: string;
  startsAtEpochMs: number; createdAt: string; variant?: string; results?: LabResult[];
  snapshot?: Record<string, unknown>;
}
export interface LabEvaluationStats {
  games: number; wins: number; winRate: number; validActionRate: number; failureRate: number; attemptedGames: number; failedGames: number; unfinishedGames?: number;
  roles: Record<string, { games: number; wins: number; winRate: number }>;
}
export interface LabEvaluation {
  gameId?: LabGameId; candidateStrategyVersionId?: string; targetRole?: string; sampleWarning?: string; operationId?: string;
  id: string; agentId: string; participantId: string; status: string; pairs: number; completedGames: number; validPairs: number;
  baseline: LabEvaluationStats; candidate: LabEvaluationStats; winRateDifference: number;
  pairedOutcomes?: { sampleSize: number; candidateOnlyWins: number; baselineOnlyWins: number; sameOutcome: number; difference?: number; interval95?: { lower: number; upper: number; method: "HOEFFDING_PAIRED_CONSERVATIVE" } };
}
export interface LabExperiment {
  trainingRuns?: import("./labTraining").LabTrainingSummary[]; activeTrainingRunId?: string;
  gameId?: LabGameId; learningMode?: "INDIVIDUAL" | "SHARED_STRATEGY"; strategyHeadId?: string;
  budgetPaused?: boolean;
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
  gameId?: LabGameId; learningMode?: "SHARED_STRATEGY"; rules?: LabWerewolfRules; initialStrategyVersionId?: string;
  name: string; participants: LabParticipantInput[]; playerCount: number; targetGames: number; wordPack?: string;
  hasBlank?: boolean; budgetPoints: number; coach: LabSelection;
}
export interface LabObservation { gameId?: LabGameId; match: LabMatch; room: { id: string; name: string; roomCode: string; gameId?: LabGameId }; state?: import("./index").GameState }
export type LabStrategyEvidence =
  | { jobId: string; actorId: string; eventIds: string[] }
  | { caseId: string; taskId: string; actorId: string; evidenceIds: string[] };
export interface LabStrategyCard {
  id: string; role: string; phases: string[]; condition: string; advice: string; exceptions: string;
  evidence: LabStrategyEvidence[];
  counterEvidence?: LabStrategyEvidence[];
}
export interface LabStrategyVersion {
  id: string; experimentId: string; sequenceNumber: number; previousId?: string; gameId: LabGameId;
  content: { cards: LabStrategyCard[] }; compatibility: Record<string, unknown>; contentHash: string;
  evidence: { matchId?: string; archiveId?: string; [key: string]: unknown }; createdAt: string;
}
export interface LabStrategyBinding { personaId: string; gameId: LabGameId; publicationId?: string; strategyVersionId?: string; mode?: "TRIAL"; action?: "PUBLISH" | "WITHDRAW"; publishedAt?: string }
export interface LabStrategyAssessmentInput { ruleError: boolean; evidenceLeak: boolean; severeContradiction: boolean; notes: string }
export interface LabStrategyAssessment extends LabStrategyAssessmentInput { strategyVersionId: string; reviewedBy: string; reviewedAt: string }
export interface LabStrategyList { items: LabStrategyVersion[]; strategyHeadId?: string; bindings: LabStrategyBinding[]; assessments?: Record<string, LabStrategyAssessment> }
export interface LabStrategyPublicationRequest { operationId: string; action: "PUBLISH" | "WITHDRAW"; strategyVersionId?: string; personaIds: string[]; mode: "TRIAL" }
export interface LabStrategyPublication extends LabStrategyPublicationRequest { id: string; createdAt: string; bindings: LabStrategyBinding[] }
export interface LabScenarioResult {
  caseId: string; personaId: string; role: string; variant: "BASELINE" | "CANDIDATE"; status: "SUCCEEDED" | "FAILED";
  action?: { type?: string; content?: string; targetPlayerId?: string; nightAction?: string }; diagnostics?: Record<string, unknown>; evidenceEventIds?: string[];
}
export interface LabScenarioRun {
  id: string; operationId: string; status: "RUNNING" | "PAUSED" | "COMPLETED" | "STOPPED"; candidateStrategyVersionId: string;
  nextIndex: number; totalCalls: number; results: LabScenarioResult[]; reason?: string;
}
export interface LabDiagnostics {
  matchId: string; sequenceNumber: number; status: string; archiveId?: string;
  failure?: { code:string; stage:string; actorName?:string; round?:number; phase?:string; modelKey?:string; jobId?:string; action?:{type:string;content:string} };
  tasks: { jobId:string; status:string; reason:string; actorName:string; modelKey:string; kind:string; attempts:{attempt:number;flags?:string[];failureStage?:string;settlementState?:string;requestId?:string}[] }[];
  calls: { requestId:string;state:string;billedPoints:number|null;purpose:string }[];
}
