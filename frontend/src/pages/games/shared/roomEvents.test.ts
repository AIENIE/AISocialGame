import { describe, expect, it } from "vitest";
import type { GameLogEntry, GameState } from "@/types";
import { systemEventMessage, eventContent, isSpeechEvent, mergeEvents, revealedBallot } from "./roomEvents";
import { roomActions } from "./roomActions";

const event = (seq: number, fields: Partial<GameLogEntry> = {}): GameLogEntry => ({ type: "SPEECH", message: `legacy ${seq}`, time: "2026-10-07T12:00:00", metadata: { eventId: `e${seq}`, publicSeq: seq, content: `body ${seq}` }, ...fields });
describe("room presentation", () => {
  it("merges snapshots and pages by stable public identity, including equal timestamps", () => {
    expect(mergeEvents([event(3), event(4)], [event(1), event(2), event(3)]).map(log => log.metadata?.publicSeq)).toEqual([1, 2, 3, 4]);
    expect(eventContent(event(1))).toBe("body 1");
    expect(eventContent(event(1, { metadata: undefined, message: "名字里：也有冒号：原文" }))).toBe("名字里：也有冒号：原文");
    expect(mergeEvents([event(1)], [event(1, { metadata: { eventId: "e1" } })])[0].metadata).toEqual({ eventId: "e1", publicSeq: 1, content: "body 1" });
  });
  it("renders speech as messages and ballots and skills as game events", () => {
    expect(isSpeechEvent(event(1, { type: "ASK_PLAYER" }))).toBe(true);
    expect(isSpeechEvent(event(1, { type: "VOTE_REVEALED" }))).toBe(false);
    expect(isSpeechEvent(event(1, { type: "HUNTER_SHOT" }))).toBe(false);
  });
  it("never derives a public ballot from private votes", () => {
    const state = { extra: { myVote: "p2" }, votes: { p1: "p2" } } as unknown as GameState;
    expect(revealedBallot(state, [])).toBeUndefined();
    expect(revealedBallot(state, [event(1, { type: "VOTE_REVEALED", actorId: "p1", targetId: "p2", roundNumber: 2 })])).toEqual({ votes: { p1: "p2" }, tally: { p2: 1 }, round: 2 });
  });
  it("uses server targets for self-protection, runoff and dead-player skills", () => {
    const actions = [{ type: "NIGHT_ACTION", label: "protect", targets: ["me"], nightAction: "GUARD_PROTECT", maxLength: 0 }];
    expect(roomActions({ extra: { ruleVersion: 2, legalActions: actions } } as unknown as GameState)).toEqual(actions);
    expect(roomActions({ extra: { ruleVersion: 2, legalActions: [] } } as unknown as GameState)).toEqual([]);
  });
});

it("shows failed AI actions as localized system skips without fabricating speech", () => {
  const log = event(1, { type: "SKIP", message: "历史文字", metadata: { reason: "REQUEST_FAILED", action: "SPEAK", actorName: "AI 1" } });
  expect(isSpeechEvent(log)).toBe(false);
  expect(systemEventMessage(log, "zh-CN")).toBe("AI 1因请求失败，跳过本次发言。");
  expect(systemEventMessage(log, "zh-TW")).toBe("AI 1因請求失敗，跳過本次發言。");
  expect(systemEventMessage(log, "en")).toBe("AI 1: speaking turn skipped (request failed).");
  expect(systemEventMessage({ ...log, metadata: { ...log.metadata, reason: "TIMEOUT", action: "VOTE" } }, "en")).toBe("AI 1: abstained (timed out).");
  expect(systemEventMessage({ ...log, metadata: {} }, "en")).toBe("历史文字");
});

 describe("AI failure details", () => {
  it.each(["OUTPUT_LIMIT_REACHED", "AI_CREDIT_INSUFFICIENT", "BUDGET_RECONCILIATION_REQUIRED"])("translates %s without turning it into speech", reason => {
    const log = { time: "2026-10-13T00:00:00Z", type: "SKIP", actorId: "ai", message: "old", metadata: { reason, action: "VOTE", actorName: "AI" } } as GameLogEntry;
    for (const language of ["zh-CN", "zh-TW", "en"]) expect(systemEventMessage(log, language)).not.toBe("old");
    expect(isSpeechEvent(log)).toBe(false);
  });
 });
