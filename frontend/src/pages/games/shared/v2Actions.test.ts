import { describe, expect, it } from "vitest";
import type { LegalAction } from "@/types";
import { buildPlayerAction, parseLegalActions } from "./v2Actions";

const vote: LegalAction = { type: "VOTE", label: "投票", targets: ["p2", "p3"], maxLength: 120 };
const skip: LegalAction = { type: "SKIP", label: "弃票", targets: [], maxLength: 0 };

describe("v2 action capabilities", () => {
  it("never renders internal host actions or malformed capabilities", () => {
    const parsed = parseLegalActions([vote, { ...vote, targets: [42] }, { ...vote, type: "HOST_VERDICT" }, { ...vote, maxLength: -1 }, null]);
    expect(parsed).toEqual([{ ...vote, nightAction: null }]);
    expect(parseLegalActions(undefined)).toEqual([]);
  });

  it("only permits server-listed targets and discards actions no longer offered", () => {
    expect(buildPlayerAction(vote, { target: "p1" }, [vote])).toBeNull();
    expect(buildPlayerAction(vote, { target: "p2" }, [])).toBeNull();
    expect(buildPlayerAction(vote, { target: "p2", content: "我暂时更怀疑这个位置。" }, [vote])).toEqual({ type: "VOTE", targetPlayerId: "p2", content: "我暂时更怀疑这个位置。" });
  });

  it("supports abstention only when skipping is also legal", () => {
    expect(buildPlayerAction(vote, { abstain: true }, [vote])).toBeNull();
    expect(buildPlayerAction(vote, { abstain: true }, [vote, skip])).toEqual({ type: "VOTE", abstain: true });
  });

  it("encodes witch healing and preserves the exact chosen night action", () => {
    const heal: LegalAction = { type: "NIGHT_ACTION", label: "救人", nightAction: "WITCH_SAVE", targets: ["p2"], maxLength: 120 };
    const poison = { ...heal, label: "用毒", nightAction: "WITCH_POISON" };
    expect(buildPlayerAction(heal, { target: "p2" }, [heal, poison])).toEqual({ type: "NIGHT_ACTION", nightAction: "WITCH_SAVE", targetPlayerId: "p2", useHeal: true });
    expect(buildPlayerAction(poison, { target: "p2" }, [heal, poison])).toEqual({ type: "NIGHT_ACTION", nightAction: "WITCH_POISON", targetPlayerId: "p2" });
  });

  it("requires bounded content for speech and keeps directed questions distinct", () => {
    const ask: LegalAction = { type: "ASK_PLAYER", label: "提问", targets: ["p2"], maxLength: 3 };
    expect(buildPlayerAction(ask, { target: "p2", content: "  " }, [ask])).toBeNull();
    expect(buildPlayerAction(ask, { target: "p2", content: "为什么呢？" }, [ask])).toBeNull();
    expect(buildPlayerAction(ask, { target: "p2", content: "🙂为什么" }, [ask])).toBeNull();
    expect(buildPlayerAction(ask, { target: "p2", content: "🙂谁？" }, [ask])).toEqual({ type: "ASK_PLAYER", targetPlayerId: "p2", content: "🙂谁？" });
  });
});
