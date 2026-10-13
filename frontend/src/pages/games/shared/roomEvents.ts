import type { GameLogEntry, GameState } from "@/types";

export const eventKey = (log: GameLogEntry) => log.metadata?.eventId ||
  `${log.time}:${log.type}:${log.actorId || ""}:${log.targetId || ""}:${log.message}`;
export const eventContent = (log: GameLogEntry) => log.metadata?.content || log.message;
export const publicSequence = (log?: GameLogEntry) => typeof log?.metadata?.publicSeq === "number" ? log.metadata.publicSeq : undefined;

/** Input pages are chronological. The public sequence never exposes private event counts. */
export function mergeEvents(...pages: GameLogEntry[][]): GameLogEntry[] {
  const events = new Map<string, GameLogEntry>();
  pages.flat().forEach(log => {
    const previous = events.get(eventKey(log));
    events.set(eventKey(log), { ...previous, ...log, metadata: { ...previous?.metadata, ...log.metadata } });
  });
  return [...events.values()].sort((a, b) => {
    const first = publicSequence(a), second = publicSequence(b);
    return first !== undefined && second !== undefined ? first - second : a.time.localeCompare(b.time);
  });
}

const SPEECH = new Set(["SPEAK", "SPEECH", "DESCRIPTION", "ASK_PLAYER", "ANSWER_PLAYER", "LAST_WORDS", "DISCUSS",
  "TURTLE_SOUP_DISCUSSION", "TURTLE_SOUP_QUESTION_PENDING", "TURTLE_SOUP_SOLUTION_PENDING", "TURTLE_SOUP_QUESTION",
  "TURTLE_SOUP_SOLUTION_SUBMITTED", "TURTLE_SOUP_HINT", "REACTION"]);
export const isSpeechEvent = (log: GameLogEntry) => SPEECH.has(log.type.toUpperCase());
export const isHostEvent = (log: GameLogEntry) => log.actorId === "HOST" ||
  ["TURTLE_SOUP_QUESTION", "TURTLE_SOUP_SOLUTION_SUBMITTED", "TURTLE_SOUP_HINT"].includes(log.type);

export function revealedBallot(state: GameState, logs: GameLogEntry[]) {
  const reveal = [...logs].reverse().find(log => log.type === "VOTE_REVEAL" || log.type === "VOTE_REVEALED");
  if (reveal?.metadata?.voteResult) return { ...reveal.metadata.voteResult, round: reveal.roundNumber };
  if (reveal?.type === "VOTE_REVEALED") {
    const votes: Record<string, string> = {}, tally: Record<string, number> = {};
    logs.filter(log => log.type === "VOTE_REVEALED" && log.roundNumber === reveal.roundNumber).forEach(log => {
      if (log.actorId) votes[log.actorId] = log.targetId || "abstain";
      if (log.targetId) tally[log.targetId] = (tally[log.targetId] || 0) + 1;
    });
    return { votes, tally, round: reveal.roundNumber };
  }
  const result = state.extra?.lastVoteResult;
  if (result?.votes && result?.tally) return { votes: result.votes as Record<string, string>, tally: result.tally as Record<string, number>, round: result.round as number };
  return undefined;
}

/** Old events retain their original text; only structured skip reasons are translated. */
export function systemEventMessage(log: GameLogEntry, language: string): string {
  const { reason, action, actorName } = log.metadata || {};
  if (!["SKIP", "TIMEOUT", "SYSTEM_LOG"].includes(log.type) || !["REQUEST_FAILED", "TIMEOUT"].includes(String(reason))
    || !["SPEAK", "VOTE"].includes(String(action)) || typeof actorName !== "string") return log.message;
  const voting = action === "VOTE", timeout = reason === "TIMEOUT";
  if (language.startsWith("en")) return `${actorName}: ${voting ? "abstained" : "speaking turn skipped"} (${timeout ? "timed out" : "request failed"}).`;
  if (language === "zh-TW" || language === "zh-HK") return `${actorName}因${timeout ? "逾時" : "請求失敗"}${voting ? "，自動棄票。" : "，跳過本次發言。"}`;
  return `${actorName}因${timeout ? "超时" : "请求失败"}${voting ? "，自动弃票。" : "，跳过本次发言。"}`;
}
