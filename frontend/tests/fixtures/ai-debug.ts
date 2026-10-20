import type { AiDebugDetail, AiDebugTurn, AiDebugUsage } from "../../src/types/aiDebug";

export function debugFixture(gameId: string) {
  const names = ["青禾", "阿岚", "橙子", "林间", "冬青", "白露", "小满", "远山", "云舟", "麦穗", "霁月", "星野"];
  const turns: AiDebugTurn[] = Array.from({ length: 125 }, (_, i) => ({
    id: `debug-job-${i}`, actorId: gameId === "turtle_soup" && i % 5 === 0 ? "__host__" : `ai-${i % 12}`,
    actorName: gameId === "turtle_soup" && i % 5 === 0 ? "AI 主持人" : names[i % 12],
    seatNumber: gameId === "turtle_soup" && i % 5 === 0 ? null : i % 12,
    roleKey: gameId === "turtle_soup" && i % 5 === 0 ? "HOST" : gameId === "werewolf" ? "WOLF" : "SPY",
    round: 11 - Math.floor(i / 12), phase: gameId === "werewolf" ? i % 2 ? "NIGHT" : "DAY_DISCUSS" : gameId === "turtle_soup" ? "QUESTIONING" : "DESCRIPTION",
    kind: gameId === "werewolf" && i % 2 ? "NIGHT_ACTION" : "SPEAK", status: i > 0 && i % 17 === 0 ? "FAILED" : "SUCCEEDED",
    terminalReason: i > 0 && i % 17 === 0 ? "OUTPUT_LIMIT_REACHED" : "MODEL_ACTION_APPLIED", modelKey: "fixture-reasoning-model",
    promptTokens: (4800 + i * 31) * (i % 5 === 0 ? 2 : 1) + (i % 5 === 0 ? 300 : 0), completionTokens: 950 + i * 4 + (i % 5 === 0 ? 220 : 0), cacheReadTokens: i === 1 ? null : 2400 + (i % 5 === 0 ? 3000 : 0),
    cacheWriteTokens: null, reasoningTokens: 600 + (i % 5 === 0 ? 120 : 0), usageAuthoritative: true, usageComplete: true,
    attemptCount: i % 5 === 0 ? 2 : 1, responseCount: i % 5 === 0 ? 2 : 1, latencyMs: 19234 + i,
    createdAt: new Date(Date.UTC(2026, 9, 20, 2, 0, 125 - i)).toISOString(), updatedAt: "2026-10-20T10:03:00", legacy: false,
  }));
  const usage = (rows: AiDebugTurn[]): AiDebugUsage => ({
    turns: rows.length, attempts: rows.reduce((n, row) => n + row.attemptCount, 0), responses: rows.reduce((n, row) => n + row.responseCount, 0),
    promptTokens: rows.reduce((n, row) => n + row.promptTokens!, 0), completionTokens: rows.reduce((n, row) => n + row.completionTokens!, 0),
    cacheReadTokens: rows.some(row => row.cacheReadTokens == null) ? null : rows.reduce((n, row) => n + row.cacheReadTokens!, 0),
    cacheWriteTokens: null, reasoningTokens: rows.reduce((n, row) => n + row.reasoningTokens!, 0), complete: rows.every(row => row.usageComplete), authoritative: true,
  });
  const summary = (instanceId: string) => {
    const groups = new Map<string, AiDebugTurn[]>();
    [...turns].reverse().forEach(row => { const key = `${row.round}:${row.phase}`; groups.set(key, [...groups.get(key) || [], row]); });
    return { instanceId, usage: usage(turns), rounds: [...groups.values()].map(rows => ({ round: rows[0].round, phase: rows[0].phase, usage: usage(rows), problems: rows.filter(row => row.status === "FAILED" || row.attemptCount > 1).length })),
      actors: [...new Map(turns.map(row => [row.actorId, { id: row.actorId, name: row.actorName, seatNumber: row.seatNumber, roleKey: row.roleKey }])).values()], captureWarning: null };
  };
  const detail = (id: string): AiDebugDetail => {
    const turn = turns.find(row => row.id === id)!;
    const prompt = turn.attemptCount > 1 ? (turn.promptTokens! - 300) / 2 : turn.promptTokens!;
    const output = turn.completionTokens! - (turn.attemptCount > 1 ? 220 : 0);
    const input = { instruction: "依据自己可见的信息进行本轮行动。", observation: { self: { displayName: turn.actorName, role: turn.roleKey },
      privateFacts: { word: "模型收到的私有词" }, persona: { name: turn.actorName, style: "认真核对线索，表达明确的不确定性。" },
      events: Array.from({ length: 120 }, (_, i) => ({ eventId: `event-${i}`, content: `第 ${i + 1} 条公开事件：请区分事实与推测。${i === 92 ? "特殊线索" : ""}` })),
      memory: { beliefs: { likelySpy: "尚无充分证据" }, reflections: ["上轮需要先核对说话顺序。"] } }, previousOutput: { malformed: true }, correction: ["请输出合法 JSON"] };
    const parsed = { action: { type: "SPEAK", content: "我先核对一下这个线索的使用场景，再听听后面两位的描述。" }, evidenceEventIds: ["event-92"], memoryUpdates: { reflection: "本轮继续区分公开证据和个人猜测。" } };
    return { turn, data: { promptVersion: "social-v2.10", inputFormatVersion: 3, actor: input.observation.self,
      modelSelection: { modelKey: "fixture-reasoning-model", thinkingMode: "ENABLED", thinkingEffort: "HIGH", configVersion: 4 },
      attempts: {
        "0": { attempt: 0, requestId: `${id}:0`, state: "RESPONSE", modelKey: turn.modelKey,
          messages: [{ role: "system", content: "完整系统提示：只根据角色可见信息决策。\n".repeat(600) }, { role: "user", content: JSON.stringify(input) }],
          reasoningKind: "TEXT", reasoningText: Array.from({ length: 180 }, (_, i) => `分析片段 ${i + 1}：比较当前公开描述与已有记忆；尚未确认的身份保持未知。`).join("\n"),
          rawOutput: turn.attemptCount > 1 || turn.status === "FAILED" ? "第一次输出不是合法 JSON，因此没有用于本局。" : JSON.stringify(parsed), parsedOutput: turn.attemptCount === 1 && turn.status === "SUCCEEDED" ? parsed : undefined, promptTokens: prompt, completionTokens: output, cacheReadTokens: turn.cacheReadTokens == null ? undefined : 2400, reasoningTokens: 600,
          usageAuthoritative: true, latencyMs: 10100, flags: turn.attemptCount > 1 || turn.status === "FAILED" ? ["INVALID_JSON_ENVELOPE"] : [], finishReason: "stop" },
        ...(turn.attemptCount > 1 ? { "1": { attempt: 1, requestId: `${id}:1`, state: "RESPONSE", modelKey: turn.modelKey, messages: [{ role: "system", content: "保持原系统提示。" }, { role: "user", content: JSON.stringify(input) }],
          reasoningKind: "SUMMARY", reasoningText: "核对公开事实后，修正为合法的行动结构。", rawOutput: JSON.stringify(parsed), parsedOutput: parsed,
          promptTokens: prompt + 300, completionTokens: 220, cacheReadTokens: 3000, reasoningTokens: 120, usageAuthoritative: true, flags: [], latencyMs: 9134, finishReason: "stop" } } : {}),
      }, appliedAction: turn.status === "SUCCEEDED" ? parsed.action : undefined, fallback: false } };
  };
  return { turns, summary, detail };
}
