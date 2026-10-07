import { expect, test } from "@playwright/test";

const user = { id: "A", nickname: "Alice", avatar: "", balanceAvailable: false };
const session = async (page: import("@playwright/test").Page) => {
  await page.addInitScript(() => sessionStorage.setItem("aisocialgame_token", "token-A"));
  await page.route("**/api/auth/me", route => route.fulfill({ json: user }));
};

test("慢认证与网络失败不挂载业务，重试恢复后区分 403 和 401", async ({ page }) => {
  await session(page);
  let calls = 0;
  let status = 403;
  await page.route("**/api/replays/my**", route => { calls++; return route.fulfill({ status, json: { message: "denied" } }); });
  let release!: () => void;
  const wait = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/auth/me", async route => { await wait; await route.abort("failed"); });
  await page.goto("/replays");
  await expect(page.getByText("正在确认登录状态…")).toBeVisible();
  expect(calls).toBe(0);
  release();
  await expect(page.getByText("登录状态读取失败，请重试。")).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem("aisocialgame_token"))).toBe("token-A");
  await page.unroute("**/api/auth/me");
  await page.route("**/api/auth/me", route => route.fulfill({ json: user }));
  await page.getByRole("button", { name: "重试", exact: true }).click();
  await expect(page.getByText("你没有权限查看这些内容。")).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem("aisocialgame_token"))).toBe("token-A");
  status = 401;
  await page.getByRole("button", { name: "重试", exact: true }).last().click();
  await expect(page.getByRole("heading", { name: "请登录后继续" })).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem("aisocialgame_token"))).toBeNull();
});

test("登录按钮完成 state 校验并回到原站内目标", async ({ page }) => {
  await page.route("**/api/auth/sso/login?**", route => {
    const state = new URL(route.request().url()).searchParams.get("state");
    return route.fulfill({ status: 302, headers: { location: `/sso/callback?code=fixture&state=${state}` } });
  });
  await page.route("**/api/auth/sso-callback", route => route.fulfill({ json: { token: "token-A", user } }));
  await page.route("**/api/auth/me", route => route.fulfill({ json: user }));
  await page.route("**/api/replays/my**", route => route.fulfill({ json: { items: [], total: 0 } }));
  await page.goto("/replays?filter=mine#archive");
  await page.getByRole("button", { name: "前往登录" }).click();
  await expect(page).toHaveURL(/\/replays\?filter=mine#archive$/);
  await expect(page.getByText("暂无数据")).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "对局回放" })).toBeVisible();
});

test("管理校验完成前不调用业务接口，失败显示重试", async ({ page }) => {
  let calls = 0;
  await page.route("**/api/admin/dashboard/summary", route => { calls++; return route.fulfill({ status: 503, json: {} }); });
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/admin/auth/me", async route => { await pending; await route.abort("failed"); });
  await page.goto("/admin");
  await expect(page.getByText("正在确认管理权限…")).toBeVisible(); expect(calls).toBe(0);
  release(); await expect(page.getByText("管理会话校验失败。")).toBeVisible(); expect(calls).toBe(0);
  await page.unroute("**/api/admin/auth/me");
  await page.route("**/api/admin/auth/me", route => route.fulfill({ json: { username: "admin", displayName: "管理员", sessionScope: "FULL" } }));
  await page.getByRole("button", { name: "重试" }).click();
  await expect(page.getByText("数据读取失败，请重试。")).toBeVisible(); expect(calls).toBe(1);
  await expect(page.getByText("本地用户数")).toHaveCount(0);
});
