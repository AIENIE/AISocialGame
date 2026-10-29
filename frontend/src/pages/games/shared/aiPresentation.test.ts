import { describe, expect, it } from "vitest";
import { presentationText, readPresentation } from "./aiPresentation";

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

});
