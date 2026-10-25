import { useEffect, useRef, useState } from "react";
import type { GameState, PlayerAction } from "@/types";
import { actionKey, buildPlayerAction, needsText } from "./v2Actions";
import { roomActions } from "./roomActions";
import { getApiErrorMessage } from "@/services/apiError";
import { useRoomText } from "./roomText";

function keepsWerewolfQuestionDraft(previous: GameState, next: GameState) {
  const before = previous.extra?.interaction;
  const after = next.extra?.interaction;
  return previous.gameId === "werewolf" && next.gameId === "werewolf"
    && previous.phase === "DAY_INTERACTION" && next.phase === "DAY_INTERACTION"
    && typeof next.extra?.archiveId === "string" && !!next.extra.archiveId
    && previous.extra?.archiveId === next.extra.archiveId && previous.round === next.round
    && before?.humanPriority === true && after?.humanPriority === false
    && before.questionTarget === "" && after.questionTarget === "" && before.completedPairs === after.completedPairs
    && roomActions(next).some(action => action.type === "ASK_PLAYER")
    && JSON.stringify(roomActions(previous)) === JSON.stringify(roomActions(next));
}

export function useRoomActionController(state: GameState, onAction: (action: PlayerAction) => Promise<unknown>) {
  const copy = useRoomText();
  const capabilities = roomActions(state);
  const primary = capabilities.filter(a => needsText(a) || a.targets.length > 0 || a.type === "NIGHT_ACTION");
  const [chosen, setChosen] = useState("");
  const [targets, setTargets] = useState<Record<string, string>>({});
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [submissionError, setSubmissionError] = useState("");
  const [submitted, setSubmitted] = useState(false);
  const [changed, setChanged] = useState(false);
  const [confirmation, setConfirmation] = useState<PlayerAction | null>(null);
  const token = String(state.extra?.phaseToken || `${state.phase}:${state.round}:${state.currentSeat ?? ""}`);
  const latestToken = useRef(token);
  const previousState = useRef(state);
  const submitting = useRef(false);
  const actionEndsAt = state.gameId === "werewolf" && capabilities.length ? state.extra?.actionEndsAt || state.phaseEndsAt : undefined;
  const parsedDeadline = actionEndsAt ? new Date(actionEndsAt).getTime() : NaN;
  const actionDeadline = Number.isFinite(parsedDeadline) ? parsedDeadline : null;
  const [expiredDeadline, setExpiredDeadline] = useState<number | null>(null);
  const actionExpired = actionDeadline !== null && (expiredDeadline === actionDeadline || Date.now() >= actionDeadline);
  useEffect(() => {
    if (actionDeadline === null) return;
    const timer = window.setTimeout(() => setExpiredDeadline(actionDeadline), Math.max(0, actionDeadline - Date.now()));
    return () => window.clearTimeout(timer);
  }, [actionDeadline]);
  useEffect(() => {
    const previous = previousState.current;
    previousState.current = state;
    if (latestToken.current === token) return;
    setChanged(true);
    latestToken.current = token;
    setSubmitted(false); setConfirmation(null); setSubmissionError("");
    if (!keepsWerewolfQuestionDraft(previous, state)) {
      setChosen(""); setTargets({}); setDrafts({});
    }
  }, [token, state]);
  useEffect(() => {
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
    if (!action || submitting.current || actionDeadline !== null && Date.now() >= actionDeadline) return;
    submitting.current = true; setBusy(true);
    const original = token;
    try {
      await onAction(action);
      setSubmissionError("");
      if (latestToken.current === original) { setDrafts({}); setTargets({}); setSubmitted(true); }
      setConfirmation(null);
    } catch (error) { setSubmissionError(getApiErrorMessage(error,copy("submissionFailed"))); }
    finally { submitting.current = false; setBusy(false); }
  };
  return { capabilities, primary, selected, key, draft, target, prepared, abstain, busy, actionExpired, submitted, changed, confirmation, submissionError,
    setConfirmation, submit, choose: (value: string) => { setChosen(value); setSubmitted(false); },
    selectTarget: (id: string) => setTargets(previous => ({ ...previous, [key]: id })),
    setDraft: (text: string) => { setDrafts(previous => ({ ...previous, [key]: text })); setSubmitted(false); } };
}
