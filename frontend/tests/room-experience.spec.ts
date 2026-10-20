import { expect, test } from "@playwright/test";
import { roomFixture, contained } from "./fixtures/room";
test.setTimeout(90_000);

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  test.describe(`${viewport.width}px room`, () => {
    test.use({ viewport });
    for (const game of ["undercover", "werewolf", "turtle_soup"]) test(`${game}: preparation, live controls, settlement and restart`, async ({ page }, info) => {
      const fixture = await roomFixture(page, game);
      await expect(page.locator(".room-roster [data-player-id]")).toHaveCount(fixture.room.maxPlayers);
      await expect(page.locator(".room-chat-composer input")).toBeVisible();
      await contained(page); await page.screenshot({ path: info.outputPath("preparation.png") });
      await page.getByTestId("game-start-btn").click();
      await expect(page.getByTestId("game-timeline")).toBeVisible();
      await expect(page.getByText("先听听大家的线索，再决定问谁。")).toBeVisible();
      await expect(page.getByTestId("game-add-ai-btn")).toHaveCount(0);
      await expect(page.locator(".room-chat-composer")).toHaveCount(0);
      await expect(page.getByTestId("werewolf-private-checks")).toHaveCount(0);
      await contained(page); await page.screenshot({ path: info.outputPath("playing.png") });
      await page.getByTestId("room-chat-toggle").click();
      await expect(page.getByRole("dialog").locator("input, textarea")).toHaveCount(0);
      await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      if (game !== "turtle_soup") {
        fixture.update({ phase: game === "werewolf" ? "DAY_VOTE" : "RUNOFF", extra: { legalActions: [{ type: "VOTE", label: "投票", targets: ["p1", "p2"], maxLength: 0 }] } });
        await page.getByTestId("room-choose-target").click();
        await expect(page.getByRole("dialog").locator('[data-player-id="p0"]')).toHaveAttribute("aria-disabled", "true");
        await page.getByRole("dialog").locator('[data-player-id="p2"]').press("Enter");
        await expect(page.getByRole("dialog")).toHaveCount(0);
        expect(fixture.actions).toHaveLength(0);
      } else {
        await page.getByRole("button", { name: "向主持提问", exact: true }).click();
        await page.getByTestId("v2-action-text").fill("窗外的天气与故事有关吗？");
        await expect(page.getByText("主持确认有效后消耗 1 次")).toBeVisible();
      }
      await page.getByTestId("v2-action-submit").click();
      await expect.poll(() => fixture.actions.length).toBe(1);
      expect(fixture.actions[0].requestId).toBeTruthy(); expect(fixture.actions[0].expectedPhaseToken).toBeTruthy();
      fixture.room.status = "WAITING";
      fixture.update({ phase: "SETTLEMENT", winner: game === "turtle_soup" ? "SOLVED" : "CIVILIAN", extra: { solution: "这扇窗户让他发现了真正的原因。", legalActions: [] } });
      await expect(page.locator('[data-stage="settlement"]')).toBeVisible();
      await expect(page.locator(".room-chat-composer input")).toBeVisible();
      await contained(page); await page.screenshot({ path: info.outputPath("settlement.png") });
      await page.getByTestId("game-start-btn").click();
      await expect(page.getByTestId("v2-action-text")).toHaveValue("");
      expect(fixture.errors).toEqual([]);
    });

    test("night skills, private details, languages and reduced keyboard viewport", async ({ page }, info) => {
      const fixture = await roomFixture(page, "werewolf");
      await page.getByTestId("game-start-btn").click();
      await expect(page.getByTestId("game-timeline")).toBeVisible();
      fixture.update({ phase: "NIGHT", currentSeat: undefined, extra: { legalActions: [{ type: "NIGHT_ACTION", nightAction: "SEER_CHECK", label: "查验", targets: ["p1", "p2"], maxLength: 0 }] } });
      await page.getByTestId("room-choose-target").click();
      await page.getByRole("dialog").locator('[data-player-id="p1"]').click();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      await expect(page.getByTestId("v2-action-submit")).toContainText("查验");
      await page.getByRole("button", { name: "我的信息 · 预言家" }).click();
      await expect(page.getByTestId("werewolf-private-checks")).toContainText("狼人");
      await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      for (const language of ["English", "繁體中文", "简体中文"]) {
        await page.locator(".room-header button").last().click(); await page.getByLabel(language, { exact: true }).click();
        await expect(page.getByRole("menu")).toHaveCount(0); await contained(page);
      }
      await page.emulateMedia({ reducedMotion: "reduce" });
      if (viewport.width < 768) { await page.setViewportSize({ width: 390, height: 430 }); await contained(page); }
      await page.screenshot({ path: info.outputPath("skill-and-keyboard-viewport.png") });
      expect(fixture.errors).toEqual([]);
    });
  });
}
