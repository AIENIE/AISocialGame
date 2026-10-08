import { expect, test, type Page } from "@playwright/test";

async function fixture(page: Page) {
  let joined = false, expiry = false, businessCalls = 0, joinCalls = 0;
  const entry = () => ({ id: "private-room", roomCode: "123456", gameId: "undercover", name: "私密号码测试", status: "WAITING", isPrivate: true, seatCount: joined ? 2 : 1, maxPlayers: 4, passwordRequired: true, joined, expiresAt: null });
  await page.addInitScript(() => sessionStorage.setItem("aisocialgame_token", "room-discovery-fixture"));
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    if (path === "/api/auth/me") return route.fulfill({ json: { id: "guest", nickname: "访客", balanceAvailable: false } });
    if (path === "/api/games/undercover" || path === "/api/games/werewolf") return route.fulfill({ json: { id: path.split("/").at(-1), name: "游戏", minPlayers: 4, maxPlayers: 12, configSchema: [] } });
    if (path === "/api/rooms/search" || path.endsWith("/entry")) {
      if (expiry) return route.fulfill({ status: 410, json: { code: "ROOM_EXPIRED", message: "房间已失效" } });
      if (path === "/api/rooms/search" && url.searchParams.get("roomCode") === "999999") return route.fulfill({ status: 404, json: { message: "房间不存在" } });
      return route.fulfill({ json: entry() });
    }
    if (path.endsWith("/join")) {
      joinCalls++;
      if (route.request().postDataJSON().password !== "secret") return route.fulfill({ status: 403, json: { code: "ROOM_PASSWORD_INVALID", message: "私密房间密码错误" } });
      joined = true; return route.fulfill({ json: { ...entry(), selfPlayerId: "guest", seats: [{ playerId: "guest", displayName: "访客", seatNumber: 1, host: false, ai: false }] } });
    }
    if (path.endsWith("/rooms")) return route.fulfill({ json: { items: [], total: 0, page: 1, size: 30 } });
    businessCalls++;
    if (path.endsWith("/state")) return route.fulfill({ json: { roomId: "private-room", gameId: "undercover", phase: "WAITING", round: 0, myPlayerId: "guest", players: [], logs: [], extra: { legalActions: [] } } });
    if (path.endsWith("/private-room")) return route.fulfill({ json: { ...entry(), seats: [{ playerId: "guest", displayName: "访客", seatNumber: 1, host: false, ai: false }], config: {} } });
    if (path.endsWith("/logs")) return route.fulfill({ json: { items: [], hasMore: false, nextCursor: null } });
    return route.fulfill({ json: [] });
  });
  await page.routeWebSocket(/\/ws$/, ws => ws.close());
  return { get businessCalls() { return businessCalls; }, get joinCalls() { return joinCalls; }, expire() { expiry = true; } };
}

for (const width of [1440, 390]) test(`房间号搜索与密码重试 ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 }); const flow = await fixture(page);
  await page.goto("/game/werewolf");
  const search = page.getByRole("region", { name: "房间号搜索" });
  await search.getByLabel("房间号", { exact: true }).fill("123"); await search.getByRole("button", { name: "房间号搜索", exact: true }).click();
  await expect(search.getByRole("alert")).toContainText("请输入六位房间号");
  await search.getByLabel("房间号", { exact: true }).fill("123456"); await search.getByRole("button", { name: "房间号搜索", exact: true }).click();
  await expect(search.getByText("私密号码测试")).toBeVisible(); expect(flow.businessCalls).toBe(0);
  await search.getByRole("button", { name: "立即加入", exact: true }).click();
  await page.getByRole("dialog").getByLabel("房间密码").fill("wrong"); await page.getByRole("dialog").getByRole("button", { name: "立即加入", exact: true }).click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText("口令不正确"); expect(flow.businessCalls).toBe(0);
  await page.getByRole("dialog").getByLabel("房间密码").fill("secret"); await page.getByRole("dialog").getByRole("button", { name: "立即加入", exact: true }).click();
  await expect(page).toHaveURL(/\/room\/undercover\/private-room$/); await expect(page.getByTestId("game-room")).toBeVisible(); expect(flow.joinCalls).toBe(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("旧私密链接取消不加载业务，失效链接显示终止状态", async ({ page }) => {
  const flow = await fixture(page); await page.goto("/room/undercover/private-room");
  await page.getByRole("button", { name: "立即加入", exact: true }).click(); await page.getByRole("dialog").getByRole("button", { name: "取消" }).click();
  expect(flow.businessCalls).toBe(0); expect(flow.joinCalls).toBe(0);
  flow.expire(); await page.reload(); await expect(page.getByRole("heading", { name: "房间已失效" })).toBeVisible(); expect(flow.businessCalls).toBe(0);
});
