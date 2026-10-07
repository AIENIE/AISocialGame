import { expect, test, type Page, type WebSocketRoute } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import type { GameState, PlayerAction, Room } from "../src/types";
test.setTimeout(90_000);

// Deterministic UI acceptance, separate from real API/AI acceptance. No model calls.
async function roomFixture(page: Page, gameId: string) {
  // Optional built-asset mode permits visual QA without replacing another checkout's server.
  // API and WebSocket traffic is still explicitly mocked below; this is not live acceptance.
  if (process.env.ROOM_UI_DIST === "1") await page.route("https://localsocialgame.testhut.top/**", async route => {
    const pathname = new URL(route.request().url()).pathname;
    const file = pathname.startsWith("/assets/") && !pathname.includes("..") ? pathname.slice(1) : "index.html";
    const contentType = file.endsWith(".js") ? "application/javascript" : file.endsWith(".css") ? "text/css" : "text/html";
    await route.fulfill({ contentType, body: await readFile(resolve("dist", file)) });
  });
  const count = gameId === "werewolf" ? 12 : 4;
  const seats = Array.from({ length: count }, (_, index) => ({ playerId: `p${index}`, displayName: index === 1 ? "名字很长也应该保持布局稳定的玩家" : ["小林", "阿岚", "橙子", "青禾"][index % 4], seatNumber: index, avatar: "", ai: index > 0, ready: true, host: index === 0 }));
  const room: Room = { id: "room-ui", gameId, name: "周三晚上的推理小局", status: "WAITING", maxPlayers: count, isPrivate: false, hostUserId: "p0", seats, config: { playerCount: count } };
  let revision = 0;
  let state: GameState = { roomId: room.id, gameId, phase: "WAITING", round: 0, myPlayerId: "p0", players: [], logs: [], extra: { ruleVersion: 2, legalActions: [] } };
  const sockets: WebSocketRoute[] = [], actions: PlayerAction[] = [], errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("console", message => { if (message.type() === "error") errors.push(message.text()); });
  await page.addInitScript(() => { sessionStorage.setItem("aisocialgame_token", "room-ui-fixture"); localStorage.setItem("aisocialgame_locale", "zh-CN"); });
  const frame = (destination: string, body: unknown) => `MESSAGE\ndestination:${destination}\n\n${JSON.stringify(body)}\0`;
  await page.routeWebSocket(/\/ws$/, ws => {
    sockets.push(ws);
    ws.onMessage(raw => {
      const message = String(raw);
      if (message.startsWith("CONNECT\n")) ws.send("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0");
      else if (message.includes(`/app/room/${room.id}/sync`)) {
        const payload = JSON.parse(message.split("\n\n")[1].replace(/\0/g, ""));
        ws.send(frame("/user/queue/private", { type: "SYNC_READY", payload: { roomId: room.id, nonce: payload.nonce } }));
      }
    });
  });
  const update = (next: Partial<GameState>) => {
    state = { ...state, ...next, extra: { ...state.extra, ...next.extra, viewVersion: `view-${++revision}`, phaseToken: `phase-${revision}` } };
    sockets.forEach(ws => ws.send(frame(`/topic/room/${room.id}/state`, { type: "STATE_SYNC", phase: state.phase, round: state.round, payload: { viewVersion: state.extra?.viewVersion } })));
  };
  const playing = () => ({ phase: gameId === "turtle_soup" ? "QUESTIONING" : gameId === "werewolf" ? "DAY_DISCUSS" : "DESCRIPTION", round: 1, currentSeat: 0, myRole: gameId === "werewolf" ? "SEER" : gameId === "turtle_soup" ? "TURTLE_SOUP_PLAYER" : "CIVILIAN", myWord: gameId === "undercover" ? "茶杯" : undefined, players: seats.map(seat => ({ ...seat, alive: true })), logs: Array.from({ length: 30 }, (_, index) => ({ type: "SPEECH", actorId: `p${index % count}`, message: "兼容原文", time: "2026-10-07T12:00:00", roundNumber: 1, metadata: { eventId: `event-${index}`, publicSeq: index + 1, content: index === 29 ? "先听听大家的线索，再决定问谁。" : "这个细节让我想起日常生活里的一个场景，我们可以再核对一下。".repeat(index === 26 ? 8 : 1) } })), extra: { archiveId: `archive-${revision}`, ruleVersion: 2, legalActions: gameId === "turtle_soup" ? ["DISCUSS", "ASK_QUESTION", "SUBMIT_SOLUTION"].map(type => ({ type, label: type, targets: [], maxLength: 300 })) : [{ type: "SPEAK", label: "发言", targets: [], maxLength: 300 }], surface: gameId === "turtle_soup" ? "一个人推开窗户，终于明白昨天发生了什么。" : undefined, questionCount: 2, maxQuestions: 12, hintCount: 0, maxHints: 2, seerChecks: [{ round: 1, targetPlayerId: "p1", result: "WOLF" }] } });
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    let body: unknown = [];
    if (url.pathname === "/api/auth/me") body = { id: "p0", username: "room-fixture", nickname: "小林", avatar: "", coins: 0, level: 1 };
    else if (url.pathname.endsWith("/start")) { room.status = "PLAYING"; update(playing()); body = state; }
    else if (url.pathname.endsWith("/action")) { actions.push(route.request().postDataJSON()); update({ extra: { legalActions: [] } }); body = state; }
    else if (url.pathname.endsWith("/state")) body = state;
    else if (url.pathname.endsWith("/logs")) body = { items: state.logs, nextCursor: null, hasMore: false };
    else if (url.pathname.endsWith(`/rooms/${room.id}`)) body = room;
    await route.fulfill({ contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.goto(`/room/${gameId}/${room.id}`);
  await expect(page.getByTestId("game-start-btn")).toBeEnabled({ timeout: 30_000 });
  return { room, update, actions, errors, get state() { return state; } };
}

async function contained(page: Page) {
  // visualViewport resize notifications arrive after setViewportSize resolves.
  await expect.poll(async () => page.getByTestId("game-room").evaluate(element => element.getBoundingClientRect().height <= window.innerHeight)).toBe(true);
  const dimensions = await page.evaluate(() => ({ width: innerWidth, height: innerHeight, scrollWidth: document.documentElement.scrollWidth, scrollHeight: document.documentElement.scrollHeight }));
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.width);
  expect(dimensions.scrollHeight).toBeLessThanOrEqual(dimensions.height + 1);
  const dock = await page.getByTestId("v2-action-panel").count() ? await page.getByTestId("v2-action-panel").boundingBox() : null;
  if (dock) { expect(dock.y).toBeGreaterThanOrEqual(0); expect(dock.y + dock.height).toBeLessThanOrEqual(dimensions.height); }
}

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  test.describe(`${viewport.width}px room`, () => {
    test.use({ viewport });
    for (const game of ["undercover", "werewolf", "turtle_soup"]) test(`${game}: preparation, live controls, settlement and restart`, async ({ page }, info) => {
      const fixture = await roomFixture(page, game);
      await expect(page.locator(".room-roster [data-player-id]")).toHaveCount(fixture.room.maxPlayers);
      await expect(page.locator(".room-chat-composer input")).toBeVisible();
      await contained(page); await page.screenshot({ path: info.outputPath("preparation.png") });
      await page.getByTestId("game-start-btn").click();
      await expect(page.getByTestId("game-timeline")).toBeVisible();
      await expect(page.getByText("先听听大家的线索，再决定问谁。")).toBeVisible();
      await expect(page.getByTestId("game-add-ai-btn")).toHaveCount(0);
      await expect(page.locator(".room-chat-composer")).toHaveCount(0);
      await expect(page.getByTestId("werewolf-private-checks")).toHaveCount(0);
      await contained(page); await page.screenshot({ path: info.outputPath("playing.png") });
      await page.getByTestId("room-chat-toggle").click();
      await expect(page.getByRole("dialog").locator("input, textarea")).toHaveCount(0);
      await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      if (game !== "turtle_soup") {
        fixture.update({ phase: game === "werewolf" ? "DAY_VOTE" : "RUNOFF", extra: { legalActions: [{ type: "VOTE", label: "投票", targets: ["p1", "p2"], maxLength: 0 }] } });
        await page.getByTestId("room-choose-target").click();
        await expect(page.getByRole("dialog").locator('[data-player-id="p0"]')).toHaveAttribute("aria-disabled", "true");
        await page.getByRole("dialog").locator('[data-player-id="p2"]').press("Enter");
        await expect(page.getByRole("dialog")).toHaveCount(0);
        expect(fixture.actions).toHaveLength(0);
      } else {
        await page.getByRole("button", { name: "向主持提问", exact: true }).click();
        await page.getByTestId("v2-action-text").fill("窗外的天气与故事有关吗？");
        await expect(page.getByText("主持确认有效后消耗 1 次")).toBeVisible();
      }
      await page.getByTestId("v2-action-submit").click();
      await expect.poll(() => fixture.actions.length).toBe(1);
      expect(fixture.actions[0].requestId).toBeTruthy(); expect(fixture.actions[0].expectedPhaseToken).toBeTruthy();
      fixture.room.status = "WAITING";
      fixture.update({ phase: "SETTLEMENT", winner: game === "turtle_soup" ? "SOLVED" : "CIVILIAN", extra: { solution: "这扇窗户让他发现了真正的原因。", legalActions: [] } });
      await expect(page.locator('[data-stage="settlement"]')).toBeVisible();
      await expect(page.locator(".room-chat-composer input")).toBeVisible();
      await contained(page); await page.screenshot({ path: info.outputPath("settlement.png") });
      await page.getByTestId("game-start-btn").click();
      await expect(page.getByTestId("v2-action-text")).toHaveValue("");
      expect(fixture.errors).toEqual([]);
    });

    test("night skills, private details, languages and reduced keyboard viewport", async ({ page }, info) => {
      const fixture = await roomFixture(page, "werewolf");
      await page.getByTestId("game-start-btn").click();
      await expect(page.getByTestId("game-timeline")).toBeVisible();
      fixture.update({ phase: "NIGHT", currentSeat: undefined, extra: { legalActions: [{ type: "NIGHT_ACTION", nightAction: "SEER_CHECK", label: "查验", targets: ["p1", "p2"], maxLength: 0 }] } });
      await page.getByTestId("room-choose-target").click();
      await page.getByRole("dialog").locator('[data-player-id="p1"]').click();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      await expect(page.getByTestId("v2-action-submit")).toContainText("查验");
      await page.getByRole("button", { name: "我的信息 · 预言家" }).click();
      await expect(page.getByTestId("werewolf-private-checks")).toContainText("狼人");
      await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      for (const language of ["English", "繁體中文", "简体中文"]) {
        await page.locator(".room-header button").last().click(); await page.getByLabel(language, { exact: true }).click();
        await expect(page.getByRole("menu")).toHaveCount(0); await contained(page);
      }
      await page.emulateMedia({ reducedMotion: "reduce" });
      if (viewport.width < 768) { await page.setViewportSize({ width: 390, height: 430 }); await contained(page); }
      await page.screenshot({ path: info.outputPath("skill-and-keyboard-viewport.png") });
      expect(fixture.errors).toEqual([]);
    });
  });
}
