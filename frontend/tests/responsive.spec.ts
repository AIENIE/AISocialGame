import { test, expect } from "@playwright/test";
test.use({ viewport: { width: 390, height: 844 } });
async function noOverflow(page: import("@playwright/test").Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
}
test("三语游客导航仅含首页指南，登录及语言切换可达", async ({ page }, testInfo) => {
  await page.route("**/api/games", route => route.fulfill({ json: [{ id: "werewolf", name: "狼人杀", tags: [], description: "", status: "active", coverUrl: "Moon" }] }));
  await page.goto("/");
  await expect(page.getByRole("link", { name: "seekerhut", exact: true })).toBeVisible();
  for (const language of ["English", "繁體中文", "简体中文"]) {
    await noOverflow(page); await expect(page.getByLabel(/钱包|Wallet/)).toHaveCount(0);
    await page.getByRole("button", { name: /选择语言|Select language|選擇語言/ }).click();
    await page.getByLabel(language).click();
  }
  await noOverflow(page); await expect(page.getByRole("button", { name: "登录", exact: true })).toBeVisible();
  await expect(page.getByText("速配", { exact: true })).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath("home-mobile.png"), fullPage: true });
});

for (const width of [390, 767, 768]) test(`建房按钮不被底部导航遮挡 ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 });
  await page.addInitScript(() => sessionStorage.setItem("aisocialgame_token", "create-layout-fixture"));
  await page.route("**/api/**", route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/auth/me") return route.fulfill({ json: { id: "fixture", nickname: "玩家", balanceAvailable: false } });
    if (path === "/api/games/werewolf") return route.fulfill({ json: { id: "werewolf", name: "狼人杀", minPlayers: 6, maxPlayers: 12, configSchema: [] } });
    return route.abort();
  });
  await page.goto("/create/werewolf");
  const create = page.getByRole("button", { name: "创建并入座", exact: true });
  await create.click({ trial: true });
  for (const position of ["top", "bottom"] as const) {
    await page.evaluate(position => window.scrollTo(0, position === "top" ? 0 : document.documentElement.scrollHeight), position);
    if (width >= 768) await create.scrollIntoViewIfNeeded();
    await expect.poll(() => create.evaluate(button => {
      const rect = button.getBoundingClientRect();
      return rect.top >= 0 && rect.bottom <= innerHeight
        && button.contains(document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2));
    })).toBe(true);
  }
  await noOverflow(page);
});
