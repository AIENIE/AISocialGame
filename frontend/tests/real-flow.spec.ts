import { expect, test } from "@playwright/test";

const TEST_USERNAME = process.env.E2E_USERNAME || "goodboy95";
const TEST_PASSWORD = process.env.E2E_PASSWORD || "";
const USER_SSO_LOGIN_URL = process.env.E2E_USER_SSO_LOGIN_URL || "https://userservice.localhut.com/sso/login";
const SSO_CALLBACK_URL = process.env.E2E_SSO_CALLBACK_URL || "https://aisocialgame.localhut.com/sso/callback";

test("真实链路：登录、兑换、兑换记录展示", async ({ page }) => {
  test.setTimeout(120_000);
  test.skip(process.env.REAL_E2E !== "1", "Set REAL_E2E=1 to run real integration flow.");

  await page.goto("/");
  await page.waitForLoadState("networkidle");

  const loginBtn = page.getByRole("button", { name: "登录" });
  if (await loginBtn.count()) {
    const ssoState = `pw-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
    const csrf = await fetchSsoCsrfToken(page, ssoState);

    const ssoLoginRes = await page.request.post(USER_SSO_LOGIN_URL, {
      form: {
        _csrf: csrf,
        redirect: SSO_CALLBACK_URL,
        state: ssoState,
        username: TEST_USERNAME,
        password: TEST_PASSWORD,
        keepDays: "3",
      },
      failOnStatusCode: false,
      maxRedirects: 0,
    });
    expect(ssoLoginRes.status()).toBe(302);
    const callbackLocation = ssoLoginRes.headers()["location"];
    expect(callbackLocation).toContain("access_token=");

    const hash = callbackLocation.split("#")[1] || "";
    const params = new URLSearchParams(hash);
    const callbackPayload = {
      accessToken: params.get("access_token") || "",
      userId: Number(params.get("user_id") || "0"),
      username: params.get("username") || "",
      sessionId: params.get("session_id") || "",
    };
    const callbackRes = await page.request.post("/api/auth/sso-callback", {
      data: callbackPayload,
      failOnStatusCode: false,
    });
    expect(callbackRes.status()).toBe(200);
    const callbackJson = await callbackRes.json();
    expect(callbackJson?.token).toBeTruthy();
    await page.evaluate((token: string) => window.sessionStorage.setItem("aisocialgame_token", token), callbackJson.token);
    await page.goto("/");
    await page.waitForLoadState("networkidle");
  }

  await page.goto("/profile?tab=wallet");
  await expect(page.getByText("余额概览")).toBeVisible({ timeout: 30000 });
  await expect(page.getByText("通用积分兑换专属积分")).toBeVisible();

  await page.getByPlaceholder("输入兑换数量（1:1）").fill("100");
  await page.getByRole("button", { name: "立即兑换" }).click({ force: true });

  await expect(page.getByText("通用积分兑换记录")).toBeVisible({ timeout: 30000 });
  await expect(page.getByText("兑换数量：100").first()).toBeVisible({ timeout: 30000 });
  await expect(page.getByText("通用积分：", { exact: false }).first()).toBeVisible();
  await expect(page.getByText("项目永久积分：", { exact: false }).first()).toBeVisible();
});

async function fetchSsoCsrfToken(page: import("@playwright/test").Page, state: string) {
  const loginPageRes = await page.request.get(USER_SSO_LOGIN_URL, {
    params: {
      redirect: SSO_CALLBACK_URL,
      state,
    },
  });
  expect(loginPageRes.status()).toBe(200);
  const html = await loginPageRes.text();
  const csrf = html.match(/name="_csrf"[^>]*value="([^"]+)"/)?.[1];
  expect(csrf, "userservice SSO login page must expose CSRF token").toBeTruthy();
  return csrf!;
}
