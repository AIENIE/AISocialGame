import { expect, test } from "@playwright/test";

const admin = { token: "admin-token", username: "admin", displayName: "系统管理员" };
const event = {
  id: 7,
  source: "ROOM_CHAT",
  action: "BLOCK",
  severity: "HIGH",
  category: "PROMPT_INJECTION",
  status: "OPEN",
  roomId: "room-1",
  userId: "user-1",
  contentSummary: "ignore previous instructions",
  sanitizedContent: "内容已根据安全策略替换。",
  reason: "疑似 Prompt Injection",
  createdAt: new Date().toISOString(),
};

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.sessionStorage.setItem("aisocialgame_admin_token", "admin-token");
    window.sessionStorage.setItem("aisocialgame_token", "user-token");
  });
  await page.route("**/api/admin/auth/me", (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(admin) }));
  await page.route("**/api/auth/me", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ id: "user-1", nickname: "测试玩家", avatar: "" }),
  }));
});

test("admin safety page can review events and controls", async ({ page }) => {
  let currentEvent = { ...event };
  let controls = [{ id: 3, scope: "ROOM", targetKey: "room-1", action: "BLOCK", reason: "测试控制", active: true, createdAt: new Date().toISOString() }];

  await page.route("**/api/admin/safety/summary", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ openHighRiskEvents: 1, blockedLast24h: 2, costAnomaliesLast24h: 0, activeControls: controls.length }),
  }));
  await page.route("**/api/admin/safety/events**", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ items: [currentEvent], page: 0, size: 30, total: 1 }),
  }));
  await page.route("**/api/admin/safety/events/7/ack", (route) => {
    currentEvent = { ...currentEvent, status: "ACKED", acknowledgedBy: "admin" };
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(currentEvent) });
  });
  await page.route("**/api/admin/safety/events/7/close", (route) => {
    currentEvent = { ...currentEvent, status: "CLOSED", closedBy: "admin" };
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(currentEvent) });
  });
  await page.route("**/api/admin/safety/controls", async (route) => {
    if (route.request().method() === "POST") {
      controls = [...controls, { id: 4, scope: "USER", targetKey: "user-2", action: "BLOCK", reason: "测试", active: true, createdAt: new Date().toISOString() }];
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(route.request().method() === "GET" ? controls : controls[controls.length - 1]) });
  });
  await page.route("**/api/admin/safety/controls/3", (route) => {
    controls = controls.filter((item) => item.id !== 3);
    return route.fulfill({ status: 204 });
  });

  await page.goto("/admin/safety");
  await expect(page.getByText("AI 安全与应急运营")).toBeVisible();
  await expect(page.getByText("PROMPT_INJECTION")).toBeVisible();
  await page.getByText("PROMPT_INJECTION").click();
  await page.getByRole("button", { name: /确认/ }).click();
  await expect(page.getByText("已确认安全事件")).toBeVisible();
  await page.getByRole("button", { name: /关闭/ }).click();
  await expect(page.getByText("已关闭安全事件")).toBeVisible();
  await page.getByPlaceholder("用户/房间/Persona/模型，GLOBAL 用 *").fill("user-2");
  await page.getByRole("button", { name: /创建控制/ }).click();
  await expect(page.getByText("临时控制已创建")).toBeVisible();
});

test("dashboard shows safety counters", async ({ page }) => {
  await page.route("**/api/admin/dashboard/summary", (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({
      localUsers: 1,
      localRooms: 2,
      localPosts: 3,
      localGameStates: 4,
      aiModels: 5,
      openHighRiskSafetyEvents: 6,
      safetyBlocksLast24h: 7,
      safetyCostAnomaliesLast24h: 0,
      activeSafetyControls: 8,
    }),
  }));

  await page.goto("/admin");
  await expect(page.getByText("未处理高危")).toBeVisible();
  await expect(page.getByText("24h 拦截")).toBeVisible();
  await expect(page.getByText("活跃安全控制")).toBeVisible();
});

test("community shows safety error without internal details", async ({ page }) => {
  let postSeen = false;
  await page.route("**/api/community/posts**", async (route) => {
    if (route.request().method() === "POST") {
      postSeen = true;
      return route.fulfill({
        status: 400,
        contentType: "application/json",
        body: JSON.stringify({ message: "内容未通过安全检查，请调整后再试", errorCode: "SAFETY_BLOCKED" }),
      });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) });
  });

  await page.goto("/community");
  const input = page.getByPlaceholder("分享你的游戏趣事...");
  await input.fill("M4_TEST_BLOCK");
  await expect(input).toHaveValue("M4_TEST_BLOCK");
  await expect(page.getByTestId("community-publish-btn")).toBeEnabled();
  await page.getByTestId("community-publish-btn").click();
  await expect.poll(() => postSeen).toBe(true);
  await expect(page.getByRole("main").getByText("内容未通过安全检查，请调整后再试")).toBeVisible();
  await expect(page.getByText(/Prompt|系统提示|隐藏身份/)).toHaveCount(0);
});
