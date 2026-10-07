import { test, expect } from "@playwright/test";

test("首页展示真实游戏目录，游客进入大厅需登录", async ({ page }) => {
  await page.route("**/api/games**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        {
          id: "werewolf",
          name: "狼人杀",
          description: "经典社交推理",
          coverUrl: "Moon",
          status: "active",
          tags: ["推理", "社交"],
          onlineCount: 12,
        },
      ]),
    });
  });

  await page.goto("/");
  await expect(page.getByText("热门游戏")).toBeVisible();
  const enterButton = page.getByRole("button", { name: /进入大厅/ }).first();
  await expect(enterButton).toBeVisible();
  await enterButton.click({ force: true });
  await page.waitForLoadState("networkidle");
  await expect(page).toHaveURL(/game/);
  await expect(page.getByRole("heading", { name: "请登录后继续" })).toBeVisible();
});
