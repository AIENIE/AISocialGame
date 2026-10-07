import { describe, expect, it } from "vitest";
import type { GameLogEntry, GameState } from "@/types";
import { eventContent, isSpeechEvent, mergeEvents, revealedBallot } from "./roomEvents";
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
