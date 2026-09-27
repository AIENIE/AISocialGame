import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { achievementApi, friendApi } from "./v2Social";

beforeEach(() => {
  localStorage.clear();
  let pending = Promise.resolve();
  vi.stubGlobal("navigator", { locks: { request: (_name: string, callback: () => unknown) => {
    const next = pending.then(callback); pending = next.then(() => undefined); return next;
  } } });
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it("queries never write and unreadable storage cannot break a layout", () => {
  const write = vi.spyOn(Storage.prototype, "setItem");
  friendApi.getPanelData("user"); achievementApi.listMyAchievements("user");
  expect(write).not.toHaveBeenCalled();
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new DOMException("denied", "SecurityError"); });
  expect(friendApi.getPanelData("user")).toEqual({ friends: [], requests: [] });
  expect(achievementApi.listMyAchievements("user")).toHaveLength(4);
  expect(write).not.toHaveBeenCalled();
});

it("rejects malformed shapes without replacing historical records", () => {
  const malformed = JSON.stringify({ user: { friends: 2, requests: null } });
  localStorage.setItem("aisocial_v2_friends", malformed);
  expect(friendApi.getPanelData("user").friends).toEqual([]);
  expect(friendApi.sendFriendRequest("user", { id: "peer", displayName: "peer", online: true })).toBe(false);
  expect(localStorage.getItem("aisocial_v2_friends")).toBe(malformed);
});

it("preserves old achievement counters and settles each archive once across concurrent requests", async () => {
  localStorage.setItem("aisocial_v2_achievements", JSON.stringify({ user: { games: 8, wins: 2, streak: 0,
    list: achievementApi.listDefinitions().map(def => ({ code: def.code, unlocked: false, progress: 0 })) } }));
  const results = await Promise.all([achievementApi.applySettlement("user", "archive", true), achievementApi.applySettlement("user", "archive", true)]);
  expect(results.every(result => result.saved)).toBe(true);
  let data = JSON.parse(localStorage.getItem("aisocial_v2_achievements") || "{}");
  expect(data.user.games).toBe(9); expect(data.user.wins).toBe(3); expect(data.user.processedArchiveIds).toEqual(["archive"]);
  await achievementApi.applySettlement("user", "another", false);
  data = JSON.parse(localStorage.getItem("aisocial_v2_achievements") || "{}");
  expect(data.user.games).toBe(10); expect(data.user.streak).toBe(0);
});

it("storage quota failure reports no successful unlock and permits a later retry", async () => {
  const write = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new DOMException("full", "QuotaExceededError"); });
  expect(await achievementApi.applySettlement("user", "archive", true)).toEqual({ saved: false, unlocked: [] });
  expect(friendApi.sendFriendRequest("user", { id: "peer", displayName: "peer", online: true })).toBe(false);
  write.mockRestore();
  expect((await achievementApi.applySettlement("user", "archive", true)).saved).toBe(true);
  expect(JSON.parse(localStorage.getItem("aisocial_v2_achievements") || "{}").user.games).toBe(1);
});
