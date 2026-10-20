import { expect, test } from "@playwright/test";
import { roomFixture } from "./fixtures/room";
import { debugFixture } from "./fixtures/ai-debug";

test.setTimeout(90_000);

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  test.describe(`${viewport.width}px AI debug`, () => {
    test.use({ viewport });
    for (const gameId of ["undercover", "werewolf", "turtle_soup"]) test(`${gameId}: all rounds, long text, retries and stable live reading`, async ({ page }, info) => {
      const fixture = await roomFixture(page, gameId, true);
      await page.getByTestId("game-start-btn").click();
      await expect(page.getByTestId("game-timeline")).toBeVisible();
      const current = String(fixture.state.extra?.archiveId);
      const data = debugFixture(gameId);
      const requests: string[] = [];
      await page.route("**/debug/ai/**", async route => {
        const url = new URL(route.request().url()); requests.push(url.pathname);
        let result: unknown;
        if (url.pathname.endsWith("/instances")) result = { items: [{ id: current, startedAt: "2026-10-20T10:00:00", turnCount: data.turns.length }, { id: "previous-match", startedAt: "2026-10-19T10:00:00", turnCount: 25 }], page: 0, size: 20, total: 2 };
        else if (url.pathname.endsWith("/summary")) result = data.summary(url.searchParams.get("instanceId")!);
        else if (url.pathname.endsWith("/turns")) {
          const rows = data.turns.filter(row => (!url.searchParams.has("round") || row.round === Number(url.searchParams.get("round")))
            && (!url.searchParams.has("phase") || row.phase === url.searchParams.get("phase"))
            && (!url.searchParams.has("actorId") || row.actorId === url.searchParams.get("actorId"))
            && (!url.searchParams.has("status") || row.status === url.searchParams.get("status")));
          const index = Number(url.searchParams.get("page") || 0); result = { items: rows.slice(index * 50, (index + 1) * 50), page: index, size: 50, total: rows.length };
        } else result = data.detail(decodeURIComponent(url.pathname.split("/").at(-1)!));
        await route.fulfill({ json: result });
      });
      await page.getByTestId("ai-debug-open").click();
      const dialog = page.getByTestId("ai-debug-workbench");
      await expect(dialog).toBeVisible(); await expect(page.getByTestId("ai-debug-turn")).toHaveCount(50);
      await expect(dialog.locator(".ai-debug-usage")).toContainText("125 次行动");
      await page.getByTestId("ai-debug-turn").first().click();
      await expect(dialog.locator(".ai-debug-detail-heading")).toContainText(gameId === "turtle_soup" ? "AI 主持人" : "青禾");
      await page.getByRole("checkbox", { name: "结构视图" }).uncheck();
      await expect(dialog.locator(".ai-debug-content")).toContainText("模型收到的私有词");
      await page.screenshot({ path: info.outputPath("input.png") });
      await page.getByRole("tab", { name: "思考", exact: true }).click();
      await expect(dialog.locator(".ai-debug-content")).toContainText("分析片段 180");
      await expect.poll(() => dialog.locator(".ai-debug-content").evaluate(element => element.scrollHeight > element.clientHeight * 2)).toBe(true);
      await page.getByLabel("查找详情文本").fill("分析片段 128");
      await expect(dialog.locator("mark[data-active]")).toContainText("分析片段 128");
      await expect(dialog.locator("mark[data-active]")).toBeInViewport();
      await page.screenshot({ path: info.outputPath("thinking.png") });
      await page.getByLabel("查找详情文本").fill("");
      await page.getByRole("button", { name: /修正 \/ 重试 1/ }).click();
      await expect(dialog.locator(".ai-debug-content")).toContainText("核对公开事实后");
      await page.getByRole("tab", { name: "输出", exact: true }).click();
      await expect(dialog.locator(".ai-debug-content")).toContainText("本局采用的动作");
      const selected = await dialog.locator('[data-testid="ai-debug-turn"][aria-pressed=true]').textContent();
      const scroll = await dialog.locator(".ai-debug-content").evaluate(element => { element.scrollTop = 80; return element.scrollTop; });
      data.turns.unshift({ ...data.turns[0], id: "new-arrival", actorName: "刚完成的角色", createdAt: "2026-10-20T10:10:00" });
      await expect(dialog.locator(".ai-debug-usage")).toContainText("126 次行动", { timeout: 10_000 });
      await expect(dialog.locator('[data-testid="ai-debug-turn"][aria-pressed=true]')).toHaveText(selected!);
      await expect.poll(() => dialog.locator(".ai-debug-content").evaluate(element => element.scrollTop)).toBe(scroll);
      await page.screenshot({ path: info.outputPath("output.png") });
      if (viewport.width < 768) await page.getByRole("button", { name: "返回行动列表" }).click();
      await page.getByRole("button", { name: "下一页" }).click();
      await expect(dialog.locator(".ai-debug-pagination")).toContainText("2 / 3");
      await page.getByLabel("筛选状态").selectOption("FAILED");
      await expect(page.getByTestId("ai-debug-turn")).toHaveCount(data.turns.filter(row => row.status === "FAILED").length);
      await page.getByLabel("筛选角色").selectOption(data.turns[0].actorId);
      await expect(dialog.locator(".ai-debug-pagination")).toContainText("1 / 1");
      await page.getByLabel("对局", { exact: true }).selectOption("previous-match");
      await expect(page.getByLabel("对局", { exact: true })).toHaveValue("previous-match");
      const bounds = await dialog.boundingBox(); expect(bounds!.x).toBeGreaterThanOrEqual(-1); expect(bounds!.y).toBeGreaterThanOrEqual(-1);
      expect(bounds!.width).toBeLessThanOrEqual(viewport.width + 1); expect(bounds!.height).toBeLessThanOrEqual(viewport.height + 1);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.getByRole("button", { name: "Close", exact: true }).click(); await expect(dialog).toHaveCount(0);
      const count = requests.length; await page.waitForTimeout(2300); expect(requests).toHaveLength(count);
      expect(fixture.errors).toEqual([]);
    });
  });
}

test("tablet merges round navigation into the filter bar and an unavailable capability has no entry", async ({ page }, info) => {
  await page.setViewportSize({ width: 1024, height: 768 });
  const fixture = await roomFixture(page, "undercover", false);
  await expect(page.getByTestId("ai-debug-open")).toHaveCount(0);
  fixture.room.aiDebugAvailable = true;
  await page.reload(); await expect(page.getByTestId("ai-debug-open")).toBeVisible();
  const data = debugFixture("undercover");
  await page.route("**/debug/ai/**", async route => {
    const path = new URL(route.request().url()).pathname;
    await route.fulfill({ json: path.endsWith("instances") ? { items: [{ id: "tablet", startedAt: "2026-10-20T10:00:00", turnCount: 125 }], page: 0, size: 20, total: 1 }
      : path.endsWith("summary") ? data.summary("tablet") : path.endsWith("turns") ? { items: data.turns.slice(0, 50), page: 0, size: 50, total: 125 } : data.detail(path.split("/").at(-1)!) });
  });
  await page.getByTestId("ai-debug-open").click();
  await expect(page.getByLabel("回合与阶段", { exact: true })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "回合导航" })).toBeHidden();
  await page.screenshot({ path: info.outputPath("tablet.png"), animations: "disabled" });
  await page.getByRole("button", { name: "Close", exact: true }).click();
  expect(fixture.errors).toEqual([]);
});
