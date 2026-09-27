import { test, expect, type APIRequestContext, type TestInfo } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

/**
 * Explicitly opt-in, chargeable acceptance. Uses HTTP only: no browser, mock,
 * admin trace, database, replay/GOD view, clock manipulation, or stage shortcut.
 *
 * PowerShell (tokens are existing sessions for THREE DIFFERENT real accounts):
 *   $env:REAL_REALISM_V2 = '1'
 *   $env:E2E_AUTH_TOKENS = '<first>,<second>,<third>'
 *   $env:REALISM_EVIDENCE_PATH = 'D:\\acceptance\\realism-v2-evidence.json'
 *   pnpm exec playwright test tests/realism-v2.spec.ts --workers=1
 *
 * The authenticated budget endpoint must confirm a persistent server ceiling
 * of 1..210 calls. This test never resets it. The separate model comparison
 * allowance (<=90) is not used here. AI seat naming also consumes this budget.
 */

type GameId = "undercover" | "werewolf" | "turtle_soup";
type Data = Record<string, unknown>;
type LegalAction = { type: string; nightAction?: string | null; targets: string[]; maxLength: number };
type Player = { playerId: string; displayName: string; seatNumber: number; alive: boolean; ai: boolean; role?: string | null; word?: string | null };
type PublicLog = { type: string; actorId?: string | null; targetId?: string | null; message: string; roundNumber?: number; phase?: string; metadata?: Data };
type State = {
  roomId: string; gameId: GameId; phase: string; round: number; winner?: string | null;
  myPlayerId: string; myRole?: string | null; myWord?: string | null;
  currentSeat?: number | null; phaseEndsAt?: string | null;
  players: Player[]; logs: PublicLog[]; extra: Data & { legalActions: LegalAction[]; phaseToken: string };
  votes?: Record<string, string>;
};
type Room = { id: string; gameId: GameId; seats: Player[]; selfPlayerId?: string; config: Data };
type Human = { token: string; playerId: string; index: number };
type Action = { type: string; content?: string; targetPlayerId?: string; nightAction?: string; useHeal?: boolean; requestId?: string; expectedPhaseToken?: string };
type Budget = { enabled: boolean; limit: number; consumed: number; runId: string };
type Failure = { method: string; path: string; status: number; elapsedMs: number; expected: boolean; kind: "http" | "transport" };
type HumanAction = { playerId: string; phase: string; round: number; type: string; nightAction?: string; targetPlayerId?: string; content?: string; elapsedMs: number };
type ScenarioEvidence = {
  gameId: GameId; humanCount: number; playerCount: number; status: "pending" | "running" | "passed" | "failed" | "not_run";
  roomId?: string; archiveId?: string; startedAt?: string; endedAt?: string; elapsedMs?: number; error?: string;
  resumedFrom?: string;
  priorHumanActionCount?: number; priorHttpFailures?: Failure[];
  phase?: string; round?: number; winner?: string | null; phases: string[];
  budgetBefore?: Budget; budgetAfter?: Budget; chargedCalls?: number;
  humanActions: HumanAction[]; publicLogs: PublicLog[]; aiPublicEventCount: number;
  firstAiPublicEventAfterMs?: number; unauthorizedFieldChecks: number; privacyViolations: string[];
  httpFailures: Failure[]; latencyMs?: { requests: number; median: number; p95: number; max: number };
  contractChecks: Record<string, boolean>; latencies: number[];
};
type Evidence = {
  evaluationSchemaVersion?: number; evaluationSetVersion?: string; batchId?: string; buildId?: string; evidenceKind?: string; inputFormatVersion?: number; memoryFormatVersion?: number;
  sourceFingerprint?: string; promptVersion?: string; schemaVersion: number; runId: string; startedAt: string; endedAt?: string; status: string;
  baseURL: string; liveCallCeiling: number; modelComparisonCallCeiling: number;
  modelComparisonExecuted: false; executionMode: string; safety: string[];
  budgetBefore?: Budget; budgetAfter?: Budget; preflightFailures: Failure[]; scenarios: ScenarioEvidence[];
};

const CLOSURE = process.env.REALISM_CLOSURE === "1";
const LIVE_LIMIT = CLOSURE ? Number(process.env.REALISM_LIVE_LIMIT) : 210;
const TOKENS = (process.env.E2E_AUTH_TOKENS || "").split(",").map(value => value.trim()).filter(Boolean);
const RUN_ID = `realism-v2-${Date.now().toString(36)}`;
const POLL_MS = 900;
const SCENARIO_TIMEOUT_MS = 15 * 60_000;
const AI_SPEECH_TYPES = new Set(["SPEAK", "SPEECH", "ASK_PLAYER", "ANSWER_PLAYER", "TURTLE_SOUP_DISCUSSION", "TURTLE_SOUP_QUESTION_PENDING"]);
const PRIVATE_EVENT_TYPES = new Set(["ROLE_ASSIGNED", "WOLF_COUNCIL", "SEER_CHECK", "GUARD_ACTION", "WITCH_ACTION", "VOTE_TARGET", "WORD_ASSIGNED"]);
const FORBIDDEN_FIELDS = new Set([
  "hostTruth", "truth", "pendingHost", "previousPropositions", "propositionHistory", "factIds", "contradictionFactIds",
  "wordKnowledgeByPlayer", "civilianKnowledge", "undercoverKnowledge", "customWords", "privateConfig", "wordPairId", "caseHints",
  "rngSeed", "seed", "randomSeed", "aiMemoriesV2", "nightQueue", "nightActor", "nightIndex", "wolfVotes", "poisonTarget", "saveTarget", "guardTarget",
  "memoryUpdates", "beliefs", "relationshipUpdates", "privateBeliefs", "privateMemory", "internalReasoning",
]);

test.describe("真实 AI v2 六局关键流程", () => {
  test.skip(process.env.REAL_REALISM_V2 !== "1", "Set REAL_REALISM_V2=1 to authorize this chargeable acceptance run.");
  test.setTimeout(95 * 60_000);

  test("每款分别 1 真人与 3 真人，正常行动到结算并记录证据", async ({ request }, testInfo) => {
    expect(TOKENS.length, "E2E_AUTH_TOKENS must contain three distinct real account sessions").toBe(3);
    expect(new Set(TOKENS).size, "Do not reuse one account as multiple humans").toBe(3);
    const evidence: Evidence = {
      ...(CLOSURE ? { evaluationSchemaVersion: 2, evaluationSetVersion: "closure-v2", batchId: process.env.REALISM_BATCH_ID, buildId: process.env.REALISM_BUILD_ID, evidenceKind: "REAL_MODEL", inputFormatVersion: 2, memoryFormatVersion: 4 } : {}),
      sourceFingerprint: CLOSURE ? process.env.REALISM_SOURCE_FINGERPRINT : undefined, promptVersion: CLOSURE ? "social-v2.7" : undefined, schemaVersion: 1, runId: RUN_ID, startedAt: new Date().toISOString(), status: "running",
      baseURL: String(testInfo.project.use.baseURL || ""), liveCallCeiling: LIVE_LIMIT, modelComparisonCallCeiling: 90,
      modelComparisonExecuted: false, executionMode: "Playwright APIRequestContext only",
      safety: ["normal authenticated player APIs", "per-player legalActions and phaseToken", "no private-state pooling between humans", "persistent server call cap; no reset", "no model mock or stage shortcut", "no credentials or full private responses in evidence"],
      preflightFailures: [], scenarios: (["undercover", "werewolf", "turtle_soup"] as GameId[]).flatMap(gameId => [1, 3].map(humanCount => ({
        gameId, humanCount, playerCount: gameId === "werewolf" ? 6 : 4, status: "pending" as const,
        phases: [], humanActions: [], publicLogs: [], aiPublicEventCount: 0,
        unauthorizedFieldChecks: 0, privacyViolations: [], httpFailures: [], contractChecks: {}, latencies: [],
      }))),
    };
    const api = new AcceptanceApi(request, evidence);
    const selected = (process.env.REALISM_SCENARIOS || "").split(",").filter(Boolean);
    if (selected.length) {
      const known = evidence.scenarios.map(s => `${s.gameId}:${s.humanCount}`);
      expect(new Set(selected).size, "Duplicate scenario selection").toBe(selected.length);
      expect(selected.every(id => known.includes(id)), "Unknown scenario selection").toBe(true);
      evidence.scenarios = evidence.scenarios.filter(s => selected.includes(`${s.gameId}:${s.humanCount}`));
      evidence.safety.push("Selected scenarios only; previous evidence is not overwritten or counted as this version passing");
    }
    const resumePath = process.env.REALISM_RESUME_EVIDENCE;
    if (resumePath) {
      expect(CLOSURE, "Final closure never resumes an older version").toBe(false);
      const prior: Evidence = JSON.parse(await readFile(resumePath, "utf8"));
      expect(prior.schemaVersion).toBe(1);
      for (const scenario of evidence.scenarios) {
        const previous = prior.scenarios.filter(s => s.gameId === scenario.gameId && s.humanCount === scenario.humanCount &&
          s.roomId && (s.status === "failed" || s.status === "running"));
        expect(previous.length, "Ambiguous interrupted room").toBeLessThanOrEqual(1);
        if (previous.length) {
          expect(previous[0].roomId).toMatch(/^[0-9a-fA-F-]{36}$/);
          expect(previous[0].playerCount).toBe(scenario.playerCount);
          scenario.roomId = previous[0].roomId; scenario.resumedFrom = prior.runId;
          scenario.phases = [...previous[0].phases];
          scenario.humanActions = [...previous[0].humanActions];
          scenario.priorHumanActionCount = previous[0].humanActions.length;
          scenario.priorHttpFailures = previous[0].httpFailures;
        }
      }
      evidence.safety.push("Resumed rooms include older-version turns; fresh-version quality must be reported separately");
    }
    try {
      evidence.budgetBefore = await api.budget();
      requireBudget(evidence.budgetBefore);
      const personas = await api.send<{ id: string }[]>("GET", "/api/personas", TOKENS[0]);
      expect(personas.length, "The normal persona catalogue must have an AI seat option").toBeGreaterThan(0);
      for (const scenario of evidence.scenarios) {
        api.scenario = scenario;
        await test.step(`${scenario.gameId}: ${scenario.humanCount} 真人 / ${scenario.playerCount} 人`, async () => {
          const start = Date.now(); scenario.status = "running"; scenario.startedAt = new Date(start).toISOString();
          try {
            scenario.budgetBefore = await api.budget(); requireBudget(scenario.budgetBefore);
            if (scenario.budgetBefore.consumed >= scenario.budgetBefore.limit) throw new Error("Persistent live-call ceiling already reached; no new room started");
            await runScenario(api, scenario, personas.map(persona => persona.id), start, () => saveEvidence(evidence, testInfo));
            scenario.status = "passed";
          } catch (error) {
            scenario.status = "failed"; scenario.error = safeError(error);
          } finally {
            scenario.endedAt = new Date().toISOString(); scenario.elapsedMs = Date.now() - start;
            scenario.budgetAfter = await api.budget().catch(() => undefined);
            if (scenario.budgetAfter && scenario.budgetBefore) scenario.chargedCalls = scenario.budgetAfter.consumed - scenario.budgetBefore.consumed;
            scenario.latencyMs = summarizeLatency(scenario.latencies);
            await saveEvidence(evidence, testInfo);
          }
        });
        // Stop on failure: an unfinished room can still have legitimate AI jobs.
        // Do not start more chargeable games until that failure is reviewed.
        if (scenario.status === "failed") break;
      }
      for (const scenario of evidence.scenarios) if (scenario.status === "pending") scenario.status = "not_run";
      evidence.budgetAfter = await api.budget(); requireBudget(evidence.budgetAfter);
      evidence.status = evidence.scenarios.every(scenario => scenario.status === "passed") ? "passed" : "failed";
    } catch (error) {
      evidence.status = "failed";
      for (const scenario of evidence.scenarios) if (scenario.status === "pending") { scenario.status = "not_run"; scenario.error = safeError(error); }
    } finally {
      evidence.endedAt = new Date().toISOString();
      await saveEvidence(evidence, testInfo);
    }
    expect(evidence.status, evidence.scenarios.filter(s => s.status !== "passed").map(s => `${s.gameId}/${s.humanCount}: ${s.error || s.status}`).join("\n")).toBe("passed");
  });
});

class AcceptanceApi {
  scenario?: ScenarioEvidence;
  private latestBudget?: Budget;
  constructor(private request: APIRequestContext, private evidence: Evidence) {}
  async send<T>(method: "GET" | "POST", path: string, token?: string, data?: unknown, expectedStatus?: number): Promise<T> {
    const started = Date.now();
    let response;
    try {
      response = await this.request.fetch(path, { method, headers: token ? { "X-Auth-Token": token } : {}, data, timeout: 45_000, maxRetries: 0 });
    } catch {
      this.failure({ method, path, status: 0, elapsedMs: Date.now() - started, expected: false, kind: "transport" });
      throw new Error(`${method} ${path}: transport failed (not retried)`);
    }
    const elapsedMs = Date.now() - started;
    this.scenario?.latencies.push(elapsedMs);
    if (!response.ok()) {
      const expected = response.status() === expectedStatus || (path.endsWith("/action") && response.status() === 409);
      this.failure({ method, path, status: response.status(), elapsedMs, expected, kind: "http" });
      if (expectedStatus === response.status()) return undefined as T;
      if (response.status() === 409 && path.endsWith("/action")) throw new StaleAction();
      throw new Error(`${method} ${path}: HTTP ${response.status()}`);
    }
    if (expectedStatus && response.status() !== expectedStatus) throw new Error(`${method} ${path}: expected HTTP ${expectedStatus}, received ${response.status()}`);
    return response.json() as Promise<T>;
  }
  private failure(failure: Failure) { (this.scenario?.httpFailures || this.evidence.preflightFailures).push(failure); }
  async budget() {
    const budget = await this.send<Budget>("GET", "/api/games/ai-validation-budget", TOKENS[0]);
    requireBudget(budget);
    if (this.latestBudget) {
      expect(budget.runId, "The persistent live-call ledger must not change during acceptance").toBe(this.latestBudget.runId);
      expect(budget.limit, "The persistent live-call ceiling must not change during acceptance").toBe(this.latestBudget.limit);
      expect(budget.consumed, "The persistent live-call ledger must never reset during acceptance").toBeGreaterThanOrEqual(this.latestBudget.consumed);
    }
    this.latestBudget = { ...budget };
    return budget;
  }
  async state(scenario: ScenarioEvidence, human: Human, elapsedMs: number): Promise<State> {
    const state = await this.send<State>("GET", statePath(scenario), human.token);
    auditState(state, scenario, human.playerId);
    recordPublic(state, scenario, elapsedMs);
    return state;
  }
}
class StaleAction extends Error { constructor() { super("State changed before action; refresh this player's capabilities"); } }

async function runScenario(api: AcceptanceApi, evidence: ScenarioEvidence, personaIds: string[], started: number, checkpoint: () => Promise<void>) {
  const resumed = Boolean(evidence.resumedFrom && evidence.roomId);
  let room = resumed ? await api.send<Room>("GET", roomPath(evidence), TOKENS[0]) : await api.send<Room>("POST", `/api/games/${evidence.gameId}/rooms`, TOKENS[0], {
    roomName: `${RUN_ID}-${evidence.gameId}-${evidence.humanCount}human`, isPrivate: false, commMode: "text", config: configFor(evidence),
  });
  evidence.roomId = room.id;
  auditRoom(room, evidence);
  await checkpoint();
  const humans: Human[] = [];
  for (let index = 0; index < evidence.humanCount; index += 1) {
    // The compatibility DTO requires this field; the server still uses the authenticated user's nickname.
    room = await api.send<Room>("POST", `${roomPath(evidence)}/join`, TOKENS[index], { displayName: `验收玩家${index + 1}` });
    auditRoom(room, evidence);
    expect(room.selfPlayerId, "Join API must identify the authenticated human").toBeTruthy();
    humans.push({ token: TOKENS[index], playerId: room.selfPlayerId!, index });
  }
  expect(new Set(humans.map(human => human.playerId)).size, "Tokens must resolve to distinct accounts").toBe(evidence.humanCount);
  for (let index = 0; !resumed && room.seats.length < evidence.playerCount; index += 1) {
    room = await api.send<Room>("POST", `${roomPath(evidence)}/ai`, TOKENS[0], { personaId: personaIds[index % personaIds.length] });
    auditRoom(room, evidence);
  }
  expect(room.seats.filter(player => !player.ai).length).toBe(evidence.humanCount);
  expect(room.seats.length).toBe(evidence.playerCount);
  const initial = resumed ? await api.send<State>("GET", statePath(evidence), TOKENS[0])
    : await api.send<State>("POST", `${roomPath(evidence)}/start`, TOKENS[0], {});
  auditState(initial, evidence, humans[0].playerId); recordPublic(initial, evidence, Date.now() - started);
  await checkpoint();
  expect(initial.extra.ruleVersion).toBe(2);
  // Check unauthenticated access and a real nonparticipant's public-only view.
  await api.send("GET", statePath(evidence), undefined, undefined, 401);
  evidence.contractChecks.unauthenticatedStateRejected = true;
  if (evidence.humanCount === 1) {
    const outsider = await api.send<State>("GET", statePath(evidence), TOKENS[1]);
    auditState(outsider, evidence, "");
    expect(outsider.extra.legalActions).toEqual([]);
    evidence.contractChecks.nonparticipantHasNoActionsOrPrivateFields = true;
  }

  let lastBudgetCheck = 0;
  while (Date.now() - started < SCENARIO_TIMEOUT_MS) {
    let acted = false;
    for (const human of humans) {
      const state = await api.state(evidence, human, Date.now() - started);
      if (state.phase === "SETTLEMENT") {
        await verifySettlement(api, evidence, humans, state, started);
        return;
      }
      if (Date.now() - lastBudgetCheck > 10_000) {
        const budget = await api.budget(); requireBudget(budget); lastBudgetCheck = Date.now();
        await checkpoint();
        if (budget.consumed >= budget.limit) throw new Error("Persistent call ceiling reached before settlement; remaining scenarios stopped");
      }
      const action = chooseAction(state, human, evidence, Date.now() - started);
      if (!action) continue;
      assertLegal(state, action);
      action.requestId = `${RUN_ID}-${randomUUID()}`;
      action.expectedPhaseToken = state.extra.phaseToken;
      const submitted = Date.now();
      try {
        const next = await api.send<State>("POST", `${roomPath(evidence)}/action`, human.token, action);
        evidence.humanActions.push({ playerId: human.playerId, phase: state.phase, round: state.round, type: action.type, nightAction: action.nightAction, targetPlayerId: action.targetPlayerId, content: action.content, elapsedMs: Date.now() - submitted });
        auditState(next, evidence, human.playerId); recordPublic(next, evidence, Date.now() - started);
        acted = true;
      } catch (error) { if (!(error instanceof StaleAction)) throw error; }
    }
    if (!acted) await new Promise(resolveWait => setTimeout(resolveWait, POLL_MS));
  }
  throw new Error(`Scenario exceeded ${SCENARIO_TIMEOUT_MS / 60_000} minutes; last visible phase ${evidence.phase}`);
}

function configFor(scenario: ScenarioEvidence): Data {
  if (scenario.gameId === "undercover") return { playerCount: 4, spyMode: "manual", spyCount: 1, hasBlank: false, wordPack: "daily", speakTime: 60 };
  if (scenario.gameId === "werewolf") return { playerCount: 6, template: "standard", witchRule: "first_night", winCondition: "side", speechTime: 60, hasLastWords: "first_night" };
  return { playerCount: 4, caseId: "midnight_train", maxQuestions: 8, aiAssist: true };
}

function chooseAction(state: State, human: Human, evidence: ScenarioEvidence, elapsedMs: number): Action | undefined {
  const legal = state.extra.legalActions || [];
  if (!legal.length) return;
  const find = (type: string) => legal.find(action => action.type === type);
  if (evidence.gameId === "turtle_soup") return soupAction(state, human, evidence, elapsedMs);
  const night = legal.filter(action => action.type === "NIGHT_ACTION");
  if (night.length) {
    const selected = night.find(action => action.nightAction === "WITCH_SAVE") || night[0];
    // A human witch does not poison randomly merely to shorten acceptance.
    // With no antidote opportunity and no public basis, preserve the poison.
    if (selected.nightAction === "WITCH_POISON" && find("SKIP")) return { type: "SKIP" };
    const target = selected.nightAction === "WITCH_SAVE" ? selected.targets[0] : chooseTarget(state, selected.targets, selected.nightAction || undefined);
    return { type: "NIGHT_ACTION", nightAction: selected.nightAction!, targetPlayerId: target, useHeal: selected.nightAction === "WITCH_SAVE", content: "按我目前掌握的信息选择这位玩家。" };
  }
  if (find("VOTE")) {
    const targetPlayerId = chooseTarget(state, find("VOTE")!.targets);
    return targetPlayerId ? { type: "VOTE", targetPlayerId } : { type: "SKIP" };
  }
  if (find("SPEAK")) return { type: "SPEAK", content: fit(evidence.gameId === "undercover" ? undercoverSpeech(state, human) : werewolfSpeech(state), find("SPEAK")!.maxLength) };
  const answer = find("ANSWER_PLAYER");
  if (answer) return { type: "ANSWER_PLAYER", targetPlayerId: answer.targets[0], content: fit(evidence.gameId === "undercover" ? `我补充一个具体特点：${ownCue(state, human)}。这只是我的理解。` : "我会先核对刚才的发言和实际票型；目前的判断只是怀疑，还不能当成已确认的身份。", answer.maxLength) };
  if (find("ASK_PLAYER") && evidence.gameId === "undercover" && state.round === 1) {
    const targetPlayerId = chooseTarget(state, find("ASK_PLAYER")!.targets);
    if (targetPlayerId) return { type: "ASK_PLAYER", targetPlayerId, content: "你能再补充一种具体场景，帮助我理解刚才的描述吗？" };
  }
  // SKIP is only submitted if explicitly offered for this actor and window.
  if (find("SKIP")) return { type: "SKIP" };
  throw new Error(`Unimplemented legal human action in ${state.gameId}/${state.phase}: ${legal.map(action => action.type).join(",")}`);
}

function soupAction(state: State, human: Human, evidence: ScenarioEvidence, elapsedMs: number): Action | undefined {
  const has = (type: string) => state.extra.legalActions.some(action => action.type === type);
  const own = evidence.humanActions.filter(action => action.playerId === human.playerId);
  const questionAsked = own.some(action => action.type === "ASK_QUESTION");
  const questionActions = evidence.humanActions.filter(action => action.type === "ASK_QUESTION");
  // Questions are ordinary hypotheses suggested by the public surface; no
  // case truth is imported. Each human reads only their own GET response.
  const questions = ["司机看到的女人，当时真的坐在车厢里面吗？", "那个女人是在车辆行驶途中离开的吗？", "警方发现的遗物是否属于司机看到的那名女人？"];
  if (!questionAsked && has("ASK_QUESTION") && !state.extra.hostThinking) {
    if (human.index > 0 && evidence.aiPublicEventCount === 0) return;
    return { type: "ASK_QUESTION", content: questions[human.index] };
  }
  if (state.extra.hostThinking) return;
  if (CLOSURE && questionActions.length >= evidence.humanCount && evidence.aiPublicEventCount > 0 && has("SUBMIT_SOLUTION")) {
    // A frozen scripted test answer checks real host acceptance and collaboration, not human blind solving.
    const answer = process.env.REALISM_SOUP_ANSWER;
    if (!answer || answer.length > 1000) throw new Error("Missing reviewed frozen soup answer");
    if (!own.some(action => action.type === "SUBMIT_SOLUTION")) return { type: "SUBMIT_SOLUTION", content: answer };
  }
  if (!CLOSURE && questionActions.length >= evidence.humanCount && evidence.aiPublicEventCount > 0 && has("REVEAL_SOLUTION")) {
    // Revealing is a real, explicitly exposed human action. Do not fabricate a
    // successful solve from a known catalogue answer to close the scenario.
    return { type: "REVEAL_SOLUTION" };
  }
  const discussions = own.filter(action => action.type === "DISCUSS").length;
  if (questionAsked && evidence.aiPublicEventCount === 0 && human.index === 0 && elapsedMs > (discussions + 1) * 30_000 && discussions < 2 && has("DISCUSS")) {
    const latest = arrayOfData(state.extra.qaHistory).at(-1);
    return { type: "DISCUSS", content: latest ? `主持刚才的答复是“${String(latest.answer || "尚未确认")}”。我们先区分司机看到的现象与真正发生的事情，大家有什么新问题？` : "先把看到的现象与真正发生的事情分开，大家能提出一个可判断的是非问题吗？" };
  }
  if (questionAsked && evidence.aiPublicEventCount === 0 && elapsedMs > 180_000) throw new Error("No visible AI teammate contribution after human question and two discussion opportunities");
  return;
}

function chooseTarget(state: State, targets: string[], nightAction?: string): string | undefined {
  const candidates = state.players.filter(player => targets.includes(player.playerId)).sort((a, b) => a.seatNumber - b.seatNumber);
  if (!candidates.length) return;
  const checks = arrayOfData(state.extra.seerChecks); // Only present in THIS seer's response.
  if (nightAction === "SEER_CHECK") return candidates.find(player => !checks.some(check => check.targetPlayerId === player.playerId))?.playerId || candidates[0].playerId;
  const confirmed = checks.find(check => check.result === "WOLF" && targets.includes(String(check.targetPlayerId)));
  if (confirmed && !nightAction) return String(confirmed.targetPlayerId);
  if (nightAction === "WOLF_KILL") {
    const council = arrayOfData(state.extra.wolfCouncil).filter(item => targets.includes(String(item.targetPlayerId)));
    if (council.length) return String(council.at(-1)!.targetPlayerId);
  }
  const ownWolfTeam = new Set(stringArray(state.extra.wolfTeam));
  const compatible = candidates.filter(player => !ownWolfTeam.has(player.playerId));
  const pool = compatible.length ? compatible : candidates;
  // A public, reproducible tie-break policy: follow a current public nominated
  // target, then a prior revealed ballot. It never reads another human's role.
  const recent = state.logs.filter(log => log.roundNumber === state.round && ["ASK_PLAYER", "VOTE_REVEALED"].includes(log.type)).reverse();
  const nominated = recent.find(log => pool.some(player => player.playerId === log.targetId));
  if (nominated?.targetId) return nominated.targetId;
  const counts = object(state.extra.lastVoteResult).tally;
  const tally = object(counts);
  pool.sort((a, b) => Number(tally[b.playerId] || 0) - Number(tally[a.playerId] || 0) || Number(b.ai) - Number(a.ai) || a.seatNumber - b.seatNumber);
  // AI is a publicly visible seat property, used only to coordinate an otherwise
  // uninformative tie so three scripted humans do not indefinitely split votes.
  return pool[0].playerId;
}

function ownCue(state: State, human: Human): string {
  const knowledge = object(state.extra.wordKnowledge);
  const forbidden = [...stringArray(knowledge.forbiddenTerms), String(state.myWord || "")].filter(Boolean);
  const cues = [...stringArray(knowledge.attributes), ...stringArray(knowledge.scenes)].filter(cue => !forbidden.some(term => normalize(cue).includes(normalize(term))));
  return cues.length ? cues[(Math.max(0, state.round - 1) + human.index) % cues.length] : "我还需要结合前面的线索判断具体范围";
}
function undercoverSpeech(state: State, human: Human): string {
  const cue = ownCue(state, human);
  return state.phase === "TIE_DEFENSE" ? `我的描述依据是“${cue}”。如果前面说得太宽泛，这是我补充的具体特点。` : `我想到的是：${cue}。先听听大家会从哪个角度描述。`;
}
function werewolfSpeech(state: State): string {
  if (state.phase === "LAST_WORDS") return "请保留已经公开的发言和票型，再核对谁前后的说法发生了变化。";
  const checks = arrayOfData(state.extra.seerChecks);
  const latest = checks.at(-1);
  if (latest) {
    const target = state.players.find(player => player.playerId === latest.targetPlayerId);
    return `我声明自己是预言家：我查验的${target ? target.seatNumber + 1 : "这"}号结果为${latest.result === "WOLF" ? "狼人" : "好人"}。大家可以把这是我的身份声明记下来，结合后面的票型判断。`;
  }
  const target = chooseTarget(state, state.players.filter(player => player.alive && player.playerId !== state.myPlayerId).map(player => player.playerId));
  const player = state.players.find(candidate => candidate.playerId === target);
  return player ? `我先关注${player.seatNumber + 1}号。这只是目前的怀疑，希望他能说明判断依据；我会结合公开发言和票型继续调整。` : "目前还缺乏足够证据，我会比较公开发言是否一致，再作判断。";
}

function assertLegal(state: State, action: Action) {
  const legal = state.extra.legalActions.find(candidate => candidate.type === action.type && (!candidate.nightAction || candidate.nightAction === action.nightAction));
  expect(legal, `Action ${action.type}/${action.nightAction || ""} must be in this human's current legalActions`).toBeTruthy();
  if (legal!.targets.length) expect(legal!.targets).toContain(action.targetPlayerId);
  if (legal!.maxLength > 0 && action.content) expect(Array.from(action.content).length).toBeLessThanOrEqual(legal!.maxLength);
  expect(state.extra.phaseToken).toBeTruthy();
}

function auditRoom(room: Room, evidence: ScenarioEvidence) {
  const forbidden = ["customWords", "usedWordPairIds", "privateConfig", "hostTruth", "solution"];
  for (const key of forbidden) checkPrivacy(!Object.hasOwn(room, key) && !Object.hasOwn(room.config || {}, key), `room.${key}`, evidence);
}

function auditState(state: State, evidence: ScenarioEvidence, viewerId: string) {
  if (CLOSURE) {
    const archiveId = state.extra.archiveId;
    expect(typeof archiveId).toBe("string"); expect(String(archiveId)).toMatch(/^[0-9a-fA-F-]{36}$/);
    if (evidence.archiveId) expect(archiveId, "Archive identity changed within a scenario").toBe(evidence.archiveId);
    else evidence.archiveId = String(archiveId);
  }
  const settled = state.phase === "SETTLEMENT";
  const me = state.players.find(player => player.playerId === viewerId);
  const extra: Partial<State["extra"]> = state.extra || {};
  const scan = (value: unknown, path: string) => {
    if (!value || typeof value !== "object") return;
    for (const [key, nested] of Object.entries(value)) {
      checkPrivacy(!FORBIDDEN_FIELDS.has(key), `${path}.${key}`, evidence);
      scan(nested, `${path}.${key}`);
    }
  };
  scan(state, "state");
  checkPrivacy(!Object.hasOwn(state, "data"), "state.data", evidence);
  if (!settled) for (const key of ["solution", "civilianWord", "undercoverWord"]) checkPrivacy(!extra[key], `extra.${key}`, evidence);
  if (!me) {
    checkPrivacy(!state.myWord && !state.myRole, "nonparticipant.myWord/myRole", evidence);
    for (const key of ["word", "wordKnowledge", "blank", "wolfTeam", "wolfCouncil", "seerChecks", "guardHistory", "wolfTarget", "nightStep", "myVote"]) checkPrivacy(!Object.hasOwn(extra, key), `nonparticipant.extra.${key}`, evidence);
  }
  for (const player of state.players) {
    if (settled) continue;
    if (state.gameId === "undercover") {
      checkPrivacy(player.playerId === viewerId || !player.word, `players.${player.playerId}.word`, evidence);
      checkPrivacy(!player.alive || !player.role || (player.playerId === viewerId && player.role === "BLANK"), `players.${player.playerId}.role`, evidence);
    }
    if (state.gameId === "werewolf" && player.playerId !== viewerId) {
      const allowed = (me?.role === "WEREWOLF" && player.role === "WEREWOLF") || (player.role === "IDIOT" && stringArray(extra.revealedIdiots).includes(player.playerId));
      checkPrivacy(!player.role || allowed, `players.${player.playerId}.role`, evidence);
    }
  }
  if (state.gameId === "werewolf") {
    const roleFields: Record<string, string[]> = { WEREWOLF: ["wolfTeam", "wolfCouncil"], SEER: ["seerChecks"], GUARD: ["guardHistory", "lastGuardTarget"], WITCH: ["wolfTarget", "antidoteRemaining", "poisonRemaining"], HUNTER: ["hunterShotAvailable"], IDIOT: ["idiotRevealed"] };
    for (const [role, fields] of Object.entries(roleFields)) for (const field of fields) checkPrivacy(me?.role === role || !Object.hasOwn(extra, field), `extra.${field} outside ${role}`, evidence);
    if (state.phase === "NIGHT" || state.phase === "DEATH_ACTION") {
      checkPrivacy(state.currentSeat == null && state.phaseEndsAt == null, "public night turn/deadline", evidence);
      if (!extra.legalActions?.length) checkPrivacy(!extra.nightStep && !extra.actionEndsAt, "inactive viewer night turn metadata", evidence);
    }
  }
  for (const log of state.logs || []) {
    checkPrivacy(!PRIVATE_EVENT_TYPES.has(log.type), `publicLogs.${log.type}`, evidence);
    if (log.type === "VOTE_CAST") checkPrivacy(state.gameId === "undercover" && !log.targetId, "publicLogs.VOTE_CAST ballot target", evidence);
    const presentation = object(log.metadata?.presentation);
    expect(Object.keys(presentation).every(key => ["emotion", "gesture", "intensity"].includes(key)), "Public presentation cannot contain arbitrary model output").toBe(true);
    if (presentation.emotion != null) expect(["neutral", "curious", "tense", "relieved", "uncertain", "amused", "determined", "disappointed"]).toContain(presentation.emotion);
    if (presentation.gesture != null) expect(["none", "pause", "nod", "shake_head", "frown", "smile", "sigh", "lean_in"]).toContain(presentation.gesture);
    if (presentation.intensity != null) expect(Number.isInteger(presentation.intensity) && Number(presentation.intensity) >= 0 && Number(presentation.intensity) <= 3).toBe(true);
  }
  if (["VOTING", "RUNOFF", "DAY_VOTE"].includes(state.phase)) {
    checkPrivacy(Object.keys(state.votes || {}).every(id => id === viewerId), "live votes belonging to another voter", evidence);
    checkPrivacy(Object.keys(object(extra.votes)).every(id => id === viewerId), "extra live votes belonging to another voter", evidence);
  }
}
function checkPrivacy(condition: boolean, path: string, evidence: ScenarioEvidence) {
  evidence.unauthorizedFieldChecks += 1;
  if (!condition) {
    evidence.privacyViolations.push(path); // Never persist the leaked secret value.
    throw new Error(`Unauthorized field exposed: ${path}`);
  }
}
function recordPublic(state: State, evidence: ScenarioEvidence, elapsedMs: number) {
  evidence.phase = state.phase; evidence.round = state.round; evidence.winner = state.winner;
  if (!evidence.phases.includes(state.phase)) evidence.phases.push(state.phase);
  evidence.publicLogs = state.logs || [];
  const aiIds = new Set(state.players.filter(player => player.ai).map(player => player.playerId));
  const speeches = evidence.publicLogs.filter(log => AI_SPEECH_TYPES.has(log.type) && aiIds.has(log.actorId || ""));
  const aiQuestions = arrayOfData(state.extra.qaHistory).filter(item => item.aiGenerated === true);
  // One AI question first appears as a pending public log and then as an answered
  // qaHistory entry. Count that teammate contribution once, using its request id.
  const pendingQuestionIds = new Set(speeches.filter(log => log.type === "TURTLE_SOUP_QUESTION_PENDING")
    .map(log => String(log.metadata?.requestId || "")).filter(Boolean));
  const answeredOnly = aiQuestions.filter(item => !pendingQuestionIds.has(String(item.id || "")));
  evidence.aiPublicEventCount = speeches.length + answeredOnly.length;
  if (evidence.aiPublicEventCount && evidence.firstAiPublicEventAfterMs === undefined) evidence.firstAiPublicEventAfterMs = elapsedMs;
}

async function verifySettlement(api: AcceptanceApi, evidence: ScenarioEvidence, humans: Human[], state: State, started: number) {
  expect(state.winner).toBeTruthy();
  for (const human of humans) {
    const view = await api.state(evidence, human, Date.now() - started);
    expect(view.phase).toBe("SETTLEMENT"); expect(view.winner).toBe(state.winner);
    expect(view.extra.legalActions).toEqual([]);
  }
  expect(evidence.aiPublicEventCount, "At least one actual visible AI teammate contribution is required").toBeGreaterThan(0);
  expect(evidence.privacyViolations).toEqual([]);
  expect(evidence.httpFailures.filter(failure => !failure.expected)).toEqual([]);
  evidence.contractChecks.allHumansObserveSameSettlement = true;
  evidence.contractChecks.visibleAiContribution = true;
  evidence.contractChecks.onlyLegalHumanActions = true;
  evidence.contractChecks.noUnauthorizedFields = true;
  if (evidence.gameId === "turtle_soup") {
    expect(evidence.humanActions.some(action => action.type === "ASK_QUESTION")).toBeTruthy();
    expect(evidence.humanActions.some(action => ["SUBMIT_SOLUTION", "REVEAL_SOLUTION"].includes(action.type))).toBeTruthy();
    expect(state.extra.solution).toBeTruthy();
    evidence.contractChecks.humanQuestionAiContributionHumanEnding = true;
    if (CLOSURE) {
      expect(state.winner, "Revealing or a failed solution is not successful solving").toBe("SOLVED");
      expect(evidence.humanActions.some(a => a.type === "REVEAL_SOLUTION")).toBe(false);
      evidence.contractChecks.successfulScriptedSolve = true;
    }
  } else {
    expect(evidence.phases).toContain(evidence.gameId === "undercover" ? "DESCRIPTION" : "NIGHT");
    expect(evidence.humanActions.length).toBeGreaterThan(0);
    evidence.contractChecks.normalCompetitiveGameSettlement = true;
  }
}

function requireBudget(budget: Budget) {
  expect(budget.enabled, "Backend must enforce its persistent live-model budget before any chargeable operation").toBe(true);
  expect(Number.isInteger(budget.limit) && budget.limit > 0 && budget.limit <= LIVE_LIMIT).toBe(true);
  expect(Number.isInteger(budget.consumed) && budget.consumed >= 0 && budget.consumed <= budget.limit).toBe(true);
  expect(budget.runId).toBeTruthy();
  if (CLOSURE) {
    expect(budget.runId).toBe(process.env.REALISM_LIVE_RUN_ID);
    expect(budget.limit).toBe(LIVE_LIMIT);
    expect(process.env.REALISM_SOURCE_FINGERPRINT).toMatch(/^[a-f0-9]{64}$/);
  }
}
async function saveEvidence(evidence: Evidence, testInfo: TestInfo) {
  const output = process.env.REALISM_EVIDENCE_PATH ? resolve(process.env.REALISM_EVIDENCE_PATH) : testInfo.outputPath("realism-v2-evidence.json");
  const cleaned = { ...evidence, scenarios: evidence.scenarios.map(({ latencies: _latencies, ...scenario }) => scenario) };
  await mkdir(dirname(output), { recursive: true });
  await writeFile(output, JSON.stringify(cleaned, null, 2), "utf8");
  if (evidence.endedAt) await testInfo.attach("realism-v2-evidence", { path: output, contentType: "application/json" });
}
function summarizeLatency(values: number[]) {
  const ordered = [...values].sort((a, b) => a - b);
  const percentile = (fraction: number) => ordered[Math.max(0, Math.ceil(ordered.length * fraction) - 1)] || 0;
  return { requests: values.length, median: percentile(0.5), p95: percentile(0.95), max: percentile(1) };
}
function statePath(evidence: ScenarioEvidence) { return `${roomPath(evidence)}/state`; }
function roomPath(evidence: ScenarioEvidence) { return `/api/games/${evidence.gameId}/rooms/${evidence.roomId}`; }
function object(value: unknown): Data { return value && typeof value === "object" && !Array.isArray(value) ? value as Data : {}; }
function arrayOfData(value: unknown): Data[] { return Array.isArray(value) ? value.map(object) : []; }
function stringArray(value: unknown): string[] { return Array.isArray(value) ? value.map(String) : []; }
function fit(value: string, maximum: number) { return maximum > 0 ? Array.from(value).slice(0, maximum).join("") : value; }
function normalize(value: string) { return value.normalize("NFKC").toLowerCase().replace(/[\p{P}\p{Z}\s\p{Cf}]+/gu, ""); }
function safeError(error: unknown) {
  let message = error instanceof Error ? error.message : String(error);
  for (const token of TOKENS) message = message.split(token).join("[redacted]");
  return message.slice(0, 1800);
}
