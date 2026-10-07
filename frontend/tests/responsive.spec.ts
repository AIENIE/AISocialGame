import { test, expect } from "@playwright/test";
test.use({ viewport: { width: 390, height: 844 } });
async function noOverflow(page: import("@playwright/test").Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
}
test("三语游客导航仅含首页指南，登录及语言切换可达", async ({ page }) => {
  await page.route("**/api/games", route => route.fulfill({ json: [{ id: "werewolf", name: "狼人杀", tags: [], description: "", status: "active", coverUrl: "Moon" }] }));
  await page.goto("/");
  for (const language of ["English", "繁體中文", "简体中文"]) {
    await noOverflow(page); await expect(page.getByLabel(/钱包|Wallet/)).toHaveCount(0);
    await page.getByRole("button", { name: /选择语言|Select language|選擇語言/ }).click();
    await page.getByLabel(language).click();
  }
  await noOverflow(page); await expect(page.getByRole("button", { name: "登录", exact: true })).toBeVisible();
  await expect(page.getByText("速配", { exact: true })).toHaveCount(0);
});
