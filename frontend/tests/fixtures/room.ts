import { expect, type Page, type WebSocketRoute } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import type { GameState, PlayerAction, Room } from "../../src/types";

// Deterministic UI acceptance, separate from real API/AI acceptance. No model calls.
export async function roomFixture(page: Page, gameId: string, debug = false) {
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
  const room: Room = { aiDebugAvailable: debug, id: "room-ui", gameId, name: "周三晚上的推理小局", status: "WAITING", maxPlayers: count, isPrivate: false, hostUserId: "p0", seats, config: { playerCount: count } };
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
    else if (url.pathname.endsWith("/entry")) body = { ...room, roomCode: "123456", joined: true, passwordRequired: false, expiresAt: null };
    else if (url.pathname.endsWith("/state")) body = state;
    else if (url.pathname.endsWith("/logs")) body = { items: state.logs, nextCursor: null, hasMore: false };
    else if (url.pathname.endsWith(`/rooms/${room.id}`)) body = room;
    await route.fulfill({ contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.goto(`/room/${gameId}/${room.id}`);
  await expect(page.getByTestId("game-start-btn")).toBeEnabled({ timeout: 30_000 });
  return { room, update, actions, errors, get state() { return state; } };
}

export async function contained(page: Page) {
  // visualViewport resize notifications arrive after setViewportSize resolves.
  await expect.poll(async () => page.getByTestId("game-room").evaluate(element => element.getBoundingClientRect().height <= window.innerHeight)).toBe(true);
  const dimensions = await page.evaluate(() => ({ width: innerWidth, height: innerHeight, scrollWidth: document.documentElement.scrollWidth, scrollHeight: document.documentElement.scrollHeight }));
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.width);
  expect(dimensions.scrollHeight).toBeLessThanOrEqual(dimensions.height + 1);
  const dock = await page.getByTestId("v2-action-panel").count() ? await page.getByTestId("v2-action-panel").boundingBox() : null;
  if (dock) { expect(dock.y).toBeGreaterThanOrEqual(0); expect(dock.y + dock.height).toBeLessThanOrEqual(dimensions.height); }
}
