import type { AiPresentation, GameLogEntry } from "@/types";

const EMOTIONS = new Set(["neutral", "curious", "tense", "relieved", "uncertain", "amused", "determined", "disappointed"]);
const GESTURES = new Set(["none", "pause", "nod", "shake_head", "frown", "smile", "sigh", "lean_in"]);

export function readPresentation(value: unknown): AiPresentation | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const source = value as Record<string, unknown>;
  const result: AiPresentation = {};
  if (typeof source.emotion === "string" && EMOTIONS.has(source.emotion)) result.emotion = source.emotion as AiPresentation["emotion"];
  if (typeof source.gesture === "string" && GESTURES.has(source.gesture)) result.gesture = source.gesture as AiPresentation["gesture"];
  if (typeof source.intensity === "number" && Number.isInteger(source.intensity) && source.intensity >= 0 && source.intensity <= 3) result.intensity = source.intensity as AiPresentation["intensity"];
  return result.emotion || result.gesture ? result : undefined;
}

/** Render one restrained cue, never raw model prose or private reasoning. */
export function presentationText(value: unknown, language = "zh-CN"): string {
  const presentation = readPresentation(value);
  if (!presentation) return "";
  const english = language.startsWith("en");
  const traditional = /TW|HK|Hant/i.test(language);
  const gestures = english
    ? { pause: "pauses briefly", nod: "gives a small nod", shake_head: "gently shakes their head", frown: "furrows their brow", smile: "smiles a little", sigh: "lets out a quiet breath", lean_in: "leans in slightly" }
    : traditional
      ? { pause: "稍稍停頓", nod: "輕輕點頭", shake_head: "輕輕搖頭", frown: "微微皺眉", smile: "露出一點笑意", sigh: "輕輕呼了口氣", lean_in: "稍稍湊近" }
      : { pause: "稍稍停顿", nod: "轻轻点头", shake_head: "轻轻摇头", frown: "微微皱眉", smile: "露出一点笑意", sigh: "轻轻呼了口气", lean_in: "稍稍凑近" };
  if (presentation.gesture && presentation.gesture !== "none") return gestures[presentation.gesture];
  if (presentation.intensity === 0) return "";
  const emotions = english
    ? { curious: "looks curious", tense: "looks a little tense", relieved: "seems relieved", uncertain: "looks uncertain", amused: "seems quietly amused", determined: "looks resolved", disappointed: "seems a little disappointed" }
    : traditional
      ? { curious: "神情好奇", tense: "有些緊張", relieved: "鬆了口氣", uncertain: "有些遲疑", amused: "帶著一點笑意", determined: "神情認真", disappointed: "有些失落" }
      : { curious: "神情好奇", tense: "有些紧张", relieved: "松了口气", uncertain: "有些迟疑", amused: "带着一点笑意", determined: "神情认真", disappointed: "有些失落" };
  return presentation.emotion && presentation.emotion !== "neutral" ? emotions[presentation.emotion] : "";
}

export function latestPublicPresentations(logs: GameLogEntry[], phase?: string): Map<string, AiPresentation> {
  const result = new Map<string, AiPresentation>();
  for (const log of logs) {
    if (!log.actorId || (phase && log.phase && log.phase !== phase)) continue;
    const presentation = readPresentation(log.metadata?.presentation);
    if (presentation) result.set(log.actorId, presentation);
    else result.delete(log.actorId);
  }
  return result;
}
