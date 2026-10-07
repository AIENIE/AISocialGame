import { useEffect, useRef, useState } from "react";
import type { GameState, PlayerAction } from "@/types";
import { actionKey, buildPlayerAction, needsText } from "./v2Actions";
import { roomActions } from "./roomActions";

export function useRoomActionController(state: GameState, onAction: (action: PlayerAction) => Promise<unknown>) {
  const capabilities = roomActions(state);
  const primary = capabilities.filter(a => needsText(a) || a.targets.length > 0 || a.type === "NIGHT_ACTION");
  const [chosen, setChosen] = useState("");
  const [targets, setTargets] = useState<Record<string, string>>({});
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [changed, setChanged] = useState(false);
  const [confirmation, setConfirmation] = useState<PlayerAction | null>(null);
  const token = String(state.extra?.phaseToken || `${state.phase}:${state.round}:${state.currentSeat ?? ""}`);
  const latestToken = useRef(token);
  const submitting = useRef(false);
  useEffect(() => {
    if (latestToken.current !== token) setChanged(true);
    latestToken.current = token;
    setChosen(""); setTargets({}); setDrafts({}); setSubmitted(false); setConfirmation(null);
    const timer = window.setTimeout(() => setChanged(false), 2500);
    return () => window.clearTimeout(timer);
  }, [token]);
  const selected = primary.find(a => actionKey(a) === chosen) || primary[0];
  const key = selected ? actionKey(selected) : "";
  const draft = drafts[key] || "";
  const abstain = selected?.type === "VOTE" && capabilities.some(a => a.type === "SKIP");
  const saved = targets[key];
  const target = saved === "__abstain__" && abstain ? saved : selected?.targets.includes(saved) ? saved : selected?.targets.length === 1 ? selected.targets[0] : "";
  const prepared = selected ? buildPlayerAction(selected, { content: draft, target, abstain: target === "__abstain__" }, capabilities) : null;
  const submit = async (action: PlayerAction | null) => {
    if (!action || submitting.current) return;
    submitting.current = true; setBusy(true);
    const original = token;
    try {
      await onAction(action);
      if (latestToken.current === original) { setDrafts({}); setTargets({}); setSubmitted(true); }
      setConfirmation(null);
    } catch { /* The runtime reports the error. Keep the draft and target for retry. */ }
    finally { submitting.current = false; setBusy(false); }
  };
  return { capabilities, primary, selected, key, draft, target, prepared, abstain, busy, submitted, changed, confirmation,
    setConfirmation, submit, choose: (value: string) => { setChosen(value); setSubmitted(false); },
    selectTarget: (id: string) => setTargets(previous => ({ ...previous, [key]: id })),
    setDraft: (text: string) => { setDrafts(previous => ({ ...previous, [key]: text })); setSubmitted(false); } };
}
