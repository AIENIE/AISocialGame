import { expect, test } from "@playwright/test";

test("SSO 回调、钱包操作、AI 流式聊天", async ({ page }) => {
  test.setTimeout(120_000);
  const user = {
    id: "local-user-1",
    externalUserId: 1001,
    username: "tester",
    nickname: "测试玩家",
    email: "tester@example.com",
    avatar: "https://example.com/avatar.png",
    coins: 1200,
    level: 3,
    balance: {
      publicPermanentTokens: 300,
      projectTempTokens: 100,
      projectPermanentTokens: 800,
      totalTokens: 1200,
    },
  };

  await page.route("**/api/auth/sso-callback", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ token: "token-1", user }),
    });
  });

  await page.route("**/api/auth/me", async (route) => {
    const token = route.request().headers()["x-auth-token"];
    if (token === "token-1") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(user) });
      return;
    }
    await route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ message: "未登录" }) });
  });

  await page.route("**/api/wallet/balance", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(user.balance) });
  });
  await page.route("**/api/wallet/checkin-status", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ checkedInToday: false, tokensGrantedToday: 0 }),
    });
  });
  await page.route("**/api/wallet/usage-records**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [{ requestId: "req-1", modelKey: "gpt-4o-mini", promptTokens: 10, completionTokens: 20, billedTokens: 30, createdAt: new Date().toISOString() }],
        page: 1,
        size: 5,
        total: 1,
      }),
    });
  });
  await page.route("**/api/wallet/ledger**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: [{ id: "1", type: "CHECKIN", tokens: 100, reason: "CHECKIN", createdAt: new Date().toISOString() }],
        page: 1,
        size: 5,
        total: 1,
      }),
    });
  });
  await page.route("**/api/wallet/redemption-history**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: [], page: 1, size: 5, total: 0 }),
    });
  });
  await page.route("**/api/wallet/exchange-history**", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ items: [], page: 1, size: 5, total: 0 }),
    });
  });
  await page.route("**/api/wallet/checkin", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        success: true,
        tokensGranted: 100,
        alreadyCheckedIn: false,
        balance: {
          publicPermanentTokens: 300,
          projectTempTokens: 100,
          projectPermanentTokens: 900,
          totalTokens: 1300,
        },
      }),
    });
  });
  await page.route("**/api/wallet/redeem", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        success: true,
        tokensGranted: 50,
        creditType: "CREDIT_TYPE_PERMANENT",
        balance: {
          publicPermanentTokens: 300,
          projectTempTokens: 100,
          projectPermanentTokens: 950,
          totalTokens: 1350,
        },
      }),
    });
  });

  await page.route("**/api/ai/chat/stream", async (route) => {
    const body = [
      'data: {"content":"你","done":false}\n',
      'data: {"content":"好","done":false}\n',
      'data: {"content":"","done":true,"modelKey":"gpt-4o-mini","promptTokens":5,"completionTokens":2}\n\n',
    ].join("");
    await route.fulfill({ status: 200, contentType: "text/event-stream", body });
  });

  const ssoState = "playwright-sso-state";
  await page.addInitScript((state) => {
    window.sessionStorage.setItem("aisocialgame_sso_state", state);
  }, ssoState);
  await page.goto(`/sso/callback#access_token=remote-token&user_id=1001&username=tester&session_id=session-1&state=${ssoState}`);
  await expect
    .poll(() => page.evaluate(() => sessionStorage.getItem("aisocialgame_token")))
    .toBe("token-1");
  await page.goto("/");

  await page.goto("/profile?tab=wallet");
  await expect(page.getByText("余额概览")).toBeVisible();
  const checkinButton = page.getByRole("button", { name: /签到领积分|今日已签到/ }).first();
  await expect(checkinButton).toBeVisible();
  const checkinLabel = (await checkinButton.textContent()) || "";
  if (checkinLabel.includes("签到领积分")) {
    await checkinButton.click({ force: true });
    await expect(page.getByRole("button", { name: "今日已签到" })).toBeVisible();
  }

  await page.goto("/ai-chat");
  await page.getByPlaceholder("输入问题，按“发送”后将通过 SSE 逐字返回").fill("你好");
  await page.getByRole("button", { name: "发送" }).click({ force: true });
  await expect(page.getByText("你好").first()).toBeVisible();
});
