import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const RUN_ID = `e2e-${Date.now().toString(36)}`;
const ADMIN_USERNAME = process.env.E2E_ADMIN_USERNAME || "admin";
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD;
const USER_TOKENS = (process.env.E2E_AUTH_TOKENS || process.env.E2E_AUTH_TOKEN || "")
  .split(",")
  .map((token) => token.trim())
  .filter(Boolean);

type Room = {
  id: string;
  gameId: string;
  seats: { playerId: string; displayName: string; ai: boolean }[];
};

type GameState = {
  phase: string;
  winner?: string;
  myPlayerId?: string;
  mySeatNumber?: number;
  myRole?: string;
  currentSeat?: number;
  pendingAction?: { type: string };
  players: { playerId: string; displayName: string; seatNumber: number; ai: boolean; alive: boolean; role?: string }[];
  votes?: Record<string, string>;
  logs?: { message: string }[];
};

test.describe("真实验收：数据、AI 对局和管理端质检", () => {
  test.skip(process.env.REAL_ACCEPTANCE !== "1", "Set REAL_ACCEPTANCE=1 to run real acceptance.");
  test.setTimeout(900_000);

  test("开箱功能与五场 AI 社交游戏闭环", async ({ page, request }) => {
    await verifyPublicPages(page);
    test.skip(!USER_TOKENS.length, "Set E2E_AUTH_TOKEN or E2E_AUTH_TOKENS for authenticated game acceptance.");
    test.skip(!ADMIN_PASSWORD, "Set E2E_ADMIN_PASSWORD for admin acceptance.");
    await seedCommunityPost(request);
    await verifyUnifiedActionEndpoint(request);

    const outcomes = [];
    outcomes.push(await runGameScenario(request, "undercover", 1, 4));
    outcomes.push(await runGameScenario(request, "undercover", 3, 6));
    outcomes.push(await runGameScenario(request, "werewolf", 1, 6));
    outcomes.push(await runGameScenario(request, "werewolf", 3, 6));
    outcomes.push(await runTurtleSoupScenario(request));

    const archiveIds: string[] = [];
    for (const outcome of outcomes) {
      expect(outcome.phase).toBe("SETTLEMENT");
      expect(outcome.winner).toBeTruthy();
      archiveIds.push(await verifyServerReplay(request, outcome.gameId, outcome.roomId));
      await verifyRoomUi(page, outcome.gameId, outcome.roomId, outcome.viewerToken);
    }

    await verifyReplayUi(page, archiveIds[0]);
    await verifyAdminAiQuality(page, request);
  });
});

async function verifyPublicPages(page: Page) {
  await page.goto("/");
  await expect(page.getByText("热门游戏")).toBeVisible({ timeout: 30_000 });

  await page.goto("/community");
  await expect(page.getByText(/综合讨论/)).toBeVisible({ timeout: 30_000 });

  await page.goto("/rankings");
  await expect(page.getByRole("heading", { name: "全服排行榜" })).toBeVisible({ timeout: 30_000 });
}

async function seedCommunityPost(request: APIRequestContext) {
  const response = await request.post("/api/community/posts", {
    headers: { "X-Guest-Name": `${RUN_ID}-acceptor` },
    data: {
      content: `${RUN_ID} 浏览器验收：社区发帖链路正常。`,
      tags: ["e2e", "验收"],
    },
  });
  expect(response.ok()).toBeTruthy();
}

async function runGameScenario(request: APIRequestContext, gameId: "undercover" | "werewolf", humanCount: number, playerCount: number) {
  const humans = buildHumans(humanCount);
  const room = await createRoom(request, gameId, humanCount, playerCount, humans[0].token);
  await joinHumans(request, room, humans);
  await fillAiSeats(request, room, playerCount);

  let state = await postState(request, gameId, room.id, "start", undefined, humans[0].token);
  state = await driveToSettlement(request, gameId, room.id, humans, state);
  return { gameId, roomId: room.id, viewerPlayerId: humans[0].playerId, viewerToken: humans[0].token, phase: state.phase, winner: state.winner };
}

async function runTurtleSoupScenario(request: APIRequestContext) {
  const humans = buildHumans(1);
  const room = await createTurtleSoupRoom(request, humans[0].token);
  await joinHumans(request, room, humans);
  await fillAiSeats(request, room, 2);

  let state = await postState(request, "turtle_soup", room.id, "start", undefined, humans[0].token);
  expect(state.phase).toBe("QUESTIONING");
  expect(String(state.extra?.surface || "")).toContain("末班车");
  expect(state.extra?.solution).toBeFalsy();

  state = await postUnifiedAction(request, "turtle_soup", room.id, {
    type: "ASK_QUESTION",
    content: "司机是否看到了红色围巾？",
  }, humans[0].token);
  expect(String(state.extra?.knownClues || "")).toContain("红色围巾");

  state = await postUnifiedAction(request, "turtle_soup", room.id, {
    type: "SUBMIT_SOLUTION",
    content: "汤底是乘客早已死亡，司机看到的是车窗反光和红色围巾，所以误以为她还在车上。",
  }, humans[0].token);
  expect(state.phase).toBe("SETTLEMENT");
  expect(state.winner).toBe("SOLVED");
  expect(String(state.extra?.solution || "")).toContain("车窗反光");
  return { gameId: "turtle_soup", roomId: room.id, viewerPlayerId: humans[0].playerId, viewerToken: humans[0].token, phase: state.phase, winner: state.winner };
}

async function verifyUnifiedActionEndpoint(request: APIRequestContext) {
  const humans = buildHumans(1);
  const room = await createRoom(request, "undercover", 1, 4, humans[0].token);
  await joinHumans(request, room, humans);
  await fillAiSeats(request, room, 4);

  let state = await postState(request, "undercover", room.id, "start", undefined, humans[0].token);
  if (state.mySeatNumber === state.currentSeat && state.phase === "DESCRIPTION") {
    state = await postUnifiedAction(request, "undercover", room.id, {
      type: "SPEAK",
      content: `${RUN_ID} unified action speech`,
    }, humans[0].token);
  }
  expect(["DESCRIPTION", "VOTING", "SETTLEMENT"]).toContain(state.phase);
}

async function verifyServerReplay(request: APIRequestContext, gameId: string, roomId: string) {
  const listResponse = await request.get("/api/replays", {
    params: { gameId, size: 50 },
  });
  expect(listResponse.ok()).toBeTruthy();
  const list = await listResponse.json();
  const archive = list.items.find((item: any) => item.roomId === roomId);
  expect(archive, `missing replay archive for ${roomId}`).toBeTruthy();
  expect(archive.eventCount).toBeGreaterThan(0);

  const godResponse = await request.get(`/api/replays/${archive.id}/events`, {
    params: { viewMode: "GOD" },
  });
  expect(godResponse.ok()).toBeTruthy();
  const godReplay = await godResponse.json();
  expect(godReplay.events.length).toBeGreaterThan(0);
  expect(godReplay.events.map((event: any) => event.seq)).toEqual([...godReplay.events.map((event: any) => event.seq)].sort((a, b) => a - b));
  expect(godReplay.events.some((event: any) => event.eventType === "GAME_START")).toBeTruthy();
  expect(godReplay.events.some((event: any) => event.eventType === "GAME_END")).toBeTruthy();
  expect(godReplay.events.some((event: any) =>
    event.eventType.includes("SPEECH")
    || event.eventType === "VOTE_CAST"
    || event.eventType === "NIGHT_RESULT"
    || event.eventType === "WOLF_KILL"
    || event.eventType === "SEER_CHECK"
    || event.eventType === "WITCH_POISON"
    || event.eventType === "TURTLE_SOUP_QUESTION"
    || event.eventType === "TURTLE_SOUP_SOLVED"
  )).toBeTruthy();

  const publicResponse = await request.get(`/api/replays/${archive.id}/events`, {
    params: { viewMode: "PUBLIC" },
  });
  expect(publicResponse.ok()).toBeTruthy();
  const publicReplay = await publicResponse.json();
  const publicTypes = publicReplay.events.map((event: any) => event.eventType);
  expect(publicTypes).not.toContain("ROLE_ASSIGNED");
  expect(publicTypes).not.toContain("WORD_ASSIGNED");
  expect(publicTypes).not.toContain("WOLF_KILL");
  expect(publicTypes).not.toContain("SEER_CHECK");
  return archive.id as string;
}

async function verifyReplayUi(page: Page, archiveId: string) {
  await page.goto("/replays");
  await expect(page.getByText("对局回放")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("AI Trace").first()).toBeVisible({ timeout: 30_000 });
  await page.goto(`/replay/${archiveId}`);
  await expect(page.getByRole("button", { name: "播放" })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "单步" })).toBeVisible();
  const counter = page.getByText(/\d+\/\d+/).first();
  await expect(counter).toBeVisible();
  await page.getByRole("button", { name: "单步" }).click();
  await expect(counter).toBeVisible();
}

async function createRoom(request: APIRequestContext, gameId: "undercover" | "werewolf", humanCount: number, playerCount: number, token: string): Promise<Room> {
  const config = gameId === "undercover"
    ? { playerCount, spyMode: "auto", hasBlank: false, wordPack: "daily", speakTime: 30 }
    : { playerCount, template: "standard", witchRule: "first_night", winCondition: "side", speechTime: 60, hasLastWords: "first_night" };
  const response = await request.post(`/api/games/${gameId}/rooms`, {
    headers: authHeaders(token),
    data: {
      roomName: `${RUN_ID}-${gameId}-${humanCount}human`,
      isPrivate: false,
      commMode: "text",
      config,
    },
  });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

async function createTurtleSoupRoom(request: APIRequestContext, token: string): Promise<Room> {
  const response = await request.post("/api/games/turtle_soup/rooms", {
    headers: authHeaders(token),
    data: {
      roomName: `${RUN_ID}-turtle-soup`,
      isPrivate: false,
      commMode: "text",
      config: {
        playerCount: 2,
        caseId: "midnight_train",
        maxQuestions: 8,
        aiAssist: false,
      },
    },
  });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

function buildHumans(humanCount: number) {
  return Array.from({ length: humanCount }, (_, index) => ({
    name: `${RUN_ID}-玩家${index + 1}`,
    token: USER_TOKENS[index] || USER_TOKENS[0],
    playerId: "",
  }));
}

async function joinHumans(request: APIRequestContext, room: Room, humans: { name: string; token: string; playerId: string }[]) {
  for (const human of humans) {
    const response = await request.post(`/api/games/${room.gameId}/rooms/${room.id}/join`, {
      headers: authHeaders(human.token),
      data: { displayName: human.name },
    });
    expect(response.ok()).toBeTruthy();
    const joined = await response.json();
    human.playerId = joined.selfPlayerId;
  }
}

async function fillAiSeats(request: APIRequestContext, room: Room, playerCount: number) {
  const personas = ["ai1", "ai2", "ai3", "ai4"];
  for (let i = 0; i < playerCount; i += 1) {
    const detailResponse = await request.get(`/api/games/${room.gameId}/rooms/${room.id}`);
    const detail: Room = await detailResponse.json();
    if (detail.seats.length >= playerCount) {
      return;
    }
    const response = await request.post(`/api/games/${room.gameId}/rooms/${room.id}/ai`, {
      headers: authHeaders(USER_TOKENS[0]),
      data: { personaId: personas[i % personas.length] },
    });
    expect(response.ok()).toBeTruthy();
  }
}

async function driveToSettlement(
  request: APIRequestContext,
  gameId: "undercover" | "werewolf",
  roomId: string,
  humans: { name: string; token: string; playerId: string }[],
  initialState: GameState,
) {
  let state = initialState;
  for (let step = 0; step < 80 && state.phase !== "SETTLEMENT"; step += 1) {
    let acted = false;
    for (const human of humans) {
      const view = await getState(request, gameId, roomId, human.token);
      if (view.phase === "SETTLEMENT") {
        return view;
      }
      if (!isSelfAlive(view, human.playerId)) {
        continue;
      }
      if (view.phase === "DESCRIPTION" && view.mySeatNumber === view.currentSeat) {
        state = await postState(request, gameId, roomId, "speak", { content: `${human.name} 描述一个不暴露身份的线索。` }, human.token);
        acted = true;
      } else if (view.phase === "DAY_DISCUSS" && view.mySeatNumber === view.currentSeat) {
        state = await postState(request, gameId, roomId, "speak", { content: `${human.name} 根据发言链给出当前怀疑对象。` }, human.token);
        acted = true;
      } else if ((view.phase === "VOTING" || view.phase === "DAY_VOTE") && !view.votes?.[human.playerId]) {
        const target = firstAliveTarget(view, human.playerId);
        state = await postState(request, gameId, roomId, "vote", { targetPlayerId: target, abstain: !target }, human.token);
        acted = true;
      } else if (view.phase === "NIGHT" && view.pendingAction) {
        const payload = buildNightPayload(view, human.playerId);
        state = await postState(request, gameId, roomId, "night-action", payload, human.token);
        acted = true;
      }
    }
    if (!acted) {
      state = await getState(request, gameId, roomId, humans[0].token);
    }
  }
  expect(state.phase).toBe("SETTLEMENT");
  return state;
}

async function getState(request: APIRequestContext, gameId: string, roomId: string, token: string): Promise<GameState> {
  const response = await request.get(`/api/games/${gameId}/rooms/${roomId}/state`, {
    headers: authHeaders(token),
  });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

async function postState(request: APIRequestContext, gameId: string, roomId: string, action: string, data?: Record<string, any>, token?: string): Promise<GameState> {
  const response = await request.post(`/api/games/${gameId}/rooms/${roomId}/${action}`, {
    headers: token ? authHeaders(token) : undefined,
    data: data || {},
  });
  if (!response.ok()) {
    throw new Error(`${gameId}/${roomId}/${action} failed: ${response.status()} ${await response.text()}`);
  }
  return response.json();
}

async function postUnifiedAction(request: APIRequestContext, gameId: string, roomId: string, data: Record<string, any>, token?: string): Promise<GameState> {
  const response = await request.post(`/api/games/${gameId}/rooms/${roomId}/action`, {
    headers: token ? authHeaders(token) : undefined,
    data,
  });
  if (!response.ok()) {
    throw new Error(`${gameId}/${roomId}/action failed: ${response.status()} ${await response.text()}`);
  }
  return response.json();
}

function firstAliveTarget(state: GameState, selfId: string) {
  return state.players.find((player) => player.alive && player.playerId !== selfId)?.playerId || "";
}

function isSelfAlive(state: GameState, selfId: string) {
  return state.players.find((player) => player.playerId === selfId)?.alive !== false;
}

function buildNightPayload(state: GameState, selfId: string) {
  const targetPlayerId = firstAliveTarget(state, selfId);
  if (state.pendingAction?.type === "WITCH") {
    return targetPlayerId ? { action: "WITCH_POISON", targetPlayerId } : { action: "WITCH_SAVE", useHeal: false };
  }
  return { action: state.pendingAction?.type || "SEER_CHECK", targetPlayerId };
}

async function verifyRoomUi(page: Page, gameId: string, roomId: string, token: string) {
  await page.addInitScript((authToken) => {
    window.sessionStorage.setItem("aisocialgame_token", authToken);
  }, token);
  await page.goto(`/room/${gameId}/${roomId}`);
  await expect(page.getByTestId("game-phase-text")).toContainText("SETTLEMENT", { timeout: 30_000 });
  await expect(page.getByTestId("game-logs-panel")).toBeVisible();
}

async function verifyAdminAiQuality(page: Page, request: APIRequestContext) {
  const loginResponse = await request.post("/api/admin/auth/login", {
    data: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD },
  });
  expect(loginResponse.ok()).toBeTruthy();
  const login = await loginResponse.json();

  const tracesResponse = await request.get("/api/admin/ai/decision-traces", {
    headers: { "X-Admin-Token": login.token },
    params: { size: 5 },
  });
  expect(tracesResponse.ok()).toBeTruthy();
  const traces = await tracesResponse.json();
  expect(traces.total).toBeGreaterThan(0);

  await page.addInitScript((token) => window.sessionStorage.setItem("aisocialgame_admin_token", token), login.token);
  await page.goto("/admin/ai");
  await expect(page.getByText("AI 运营与质检")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("tab", { name: "决策 Trace" })).toBeVisible();
}

function authHeaders(token: string) {
  return { "X-Auth-Token": token };
}
