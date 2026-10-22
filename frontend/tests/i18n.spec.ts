import { test, expect } from "@playwright/test";

/**
 * i18n 冒烟测试：语言选择器真实切换 zh-CN / zh-TW / en 后，
 * 用户端系统 UI 文案随语言变化（默认简体中文）。
 */
test("用户端语言切换后系统 UI 文案随语言变化", async ({ page }) => {
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
  await expect(page.getByRole("link", { name: "seekerhut", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "seekerhut", exact: true }).locator("img")).toBeVisible();
  await expect(page).toHaveTitle("seekerhut - 在线社交推理游戏平台");
  // 默认简体中文
  await expect(page.getByText("热门游戏")).toBeVisible();

  const languageButton = page.getByRole("button", { name: "选择语言" });
  await languageButton.click();

  // 切换到 English
  await page.getByLabel("English").click();
  await expect(page.getByText("Hot Games")).toBeVisible();
  await expect(page.getByText("Play anywhere,")).toBeVisible();
  await expect(page).toHaveTitle("seekerhut - Online Social Deduction Games");

  // 切换到繁體中文
  await page.getByRole("button", { name: "Select language" }).click();
  await page.getByLabel("繁體中文").click();
  await expect(page.getByText("熱門遊戲")).toBeVisible();
  await expect(page).toHaveTitle("seekerhut - 線上社交推理遊戲平台");

  // 切回简体中文
  await page.getByRole("button", { name: "選擇語言" }).click();
  await page.getByLabel("简体中文").click();
  await expect(page.getByText("热门游戏")).toBeVisible();

  // 标题随语言同步
  await expect(page).toHaveTitle("seekerhut - 在线社交推理游戏平台");
});
