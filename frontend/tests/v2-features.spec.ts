import { test, expect } from "@playwright/test";
test("游客仅有首页和指南，旧受限入口显示登录引导", async ({ page }) => {
  let privateCalls = 0;
  await page.route("**/api/**", async route => { const url = new URL(route.request().url()); if (url.pathname !== "/api/games") privateCalls++; await route.fulfill({ status: 200, json: [] }); });
  for (const path of ["/profile", "/community", "/rankings", "/achievements", "/replays", "/replay/old", "/create/werewolf", "/room/werewolf/old", "/spectate/werewolf/old", "/ai-chat", "/game/werewolf"]) {
    await page.goto(path); await expect(page.getByRole("heading", { name: "请登录后继续" })).toBeVisible();
    await expect(page.getByLabel("钱包", { exact: true })).toHaveCount(0);
  }
  expect(privateCalls).toBe(0);
  await page.goto("/guide"); await expect(page.getByRole("heading", { name: "新手引导与规则百科" })).toBeVisible();
});
test("登录用户旧成就链接显示不可用且没有模拟功能导航", async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem("aisocialgame_token", "fixture-token"));
  await page.route("**/api/auth/me", route => route.fulfill({ json: { id: "fixture", nickname: "真实身份", balanceAvailable: false } }));
  await page.goto("/achievements"); await expect(page.getByRole("heading", { name: "此功能目前不可用" })).toBeVisible();
  await expect(page.getByRole("link", { name: "成就中心" })).toHaveCount(0);
  await expect(page.getByLabel("钱包", { exact: true })).toBeVisible();
  await expect(page.locator("header")).not.toContainText("好友请求");
});
