import { describe, expect, it } from "vitest";
import type { GameLogEntry } from "@/types";
import { latestPublicPresentations, presentationText, readPresentation } from "./aiPresentation";

describe("public presentation", () => {
  it("renders one small cue even when both gesture and emotion are provided", () => {
    expect(presentationText({ emotion: "tense", gesture: "nod", intensity: 3 })).toBe("轻轻点头");
    expect(presentationText({ emotion: "uncertain", gesture: "none", intensity: 1 }, "en")).toBe("looks uncertain");
    expect(presentationText({ gesture: "shake_head" }, "zh-TW")).toBe("輕輕搖頭");
  });

  it("does not expose arbitrary model strings or neutral emotion", () => {
    expect(presentationText({ emotion: "secret role", gesture: "hidden answer", text: "private reasoning" })).toBe("");
    expect(presentationText({ emotion: "neutral", gesture: "none", intensity: 3 })).toBe("");
    expect(presentationText({ emotion: "curious", intensity: 0 })).toBe("");
    expect(readPresentation("*stands up*" )).toBeUndefined();
  });

  it("keeps legacy logs compatible and clears stale gestures on a new neutral speech", () => {
    const logs: GameLogEntry[] = [
      { type: "speech", message: "早一点的发言", time: "2026-09-12T12:00:00", actorId: "p1", phase: "SPEECH", metadata: { presentation: { gesture: "nod" } } },
      { type: "speech", message: "现在的发言", time: "2026-09-12T12:00:02", actorId: "p1", phase: "SPEECH" },
      { type: "speech", message: "另一个玩家", time: "2026-09-12T12:00:03", actorId: "p2", phase: "SPEECH", metadata: { presentation: { gesture: "pause" } } },
      { type: "legacy", message: "旧日志", time: "2026-09-12T12:00:04" },
    ];
    expect(latestPublicPresentations(logs, "SPEECH").has("p1")).toBe(false);
    expect(latestPublicPresentations(logs, "SPEECH").get("p2")).toEqual({ gesture: "pause" });
    expect(latestPublicPresentations(logs, "VOTING").size).toBe(0);
  });
});
