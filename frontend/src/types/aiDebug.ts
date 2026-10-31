export interface AiDebugUsage {
  turns: number; attempts: number; responses: number;
  promptTokens: number | null; completionTokens: number | null;
  cacheReadTokens: number | null; cacheWriteTokens: number | null; reasoningTokens: number | null;
  complete: boolean; authoritative: boolean | null;
}
export interface AiDebugInstance { id: string; startedAt: string; turnCount: number }
export interface AiDebugTurn {
  id: string; actorId: string; actorName: string; seatNumber: number | null; roleKey: string;
  round: number; phase: string; kind: string; status: string; terminalReason: string; modelKey: string;
  promptTokens: number | null; completionTokens: number | null; cacheReadTokens: number | null;
  cacheWriteTokens: number | null; reasoningTokens: number | null; usageAuthoritative: boolean | null;
  usageComplete: boolean; attemptCount: number; responseCount: number; latencyMs: number | null;
  createdAt: string; updatedAt: string;
}
export interface AiDebugSummary {
  instanceId: string; usage: AiDebugUsage;
  rounds: { round: number; phase: string; usage: AiDebugUsage; problems: number }[];
  actors: { id: string; name: string; seatNumber: number | null; roleKey: string }[];
  captureWarning: string | null;
}
export interface AiDebugAttempt {
  attempt: number; requestId: string; state: string; modelKey?: string;
  messages?: { role: string; content: string }[];
  rawOutput?: string; parsedOutput?: Record<string, unknown>;
  reasoningText?: string; reasoningKind?: string;
  promptTokens?: number; completionTokens?: number; cacheReadTokens?: number; cacheWriteTokens?: number;
  reasoningTokens?: number; usageAuthoritative?: boolean;
  latencyMs?: number; flags?: string[]; finishReason?: string;
  budgetId?: string; settlementState?: string; requestTimeoutMs?: number;
  parseMs?: number; validationMs?: number; failureStage?: string;
}
export interface AiDebugDetail {
  turn: AiDebugTurn;
  data: {
    attempts?: Record<string, AiDebugAttempt>;
    modelSelection?: Record<string, unknown>; appliedAction?: Record<string, unknown>;
    actor?: Record<string, unknown>; persona?: Record<string, unknown>;
    promptVersion?: string; inputFormatVersion?: number;
  };
}
