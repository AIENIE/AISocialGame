import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { ArrowRight, HelpCircle, LoaderCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import type { GameState, LegalAction, PlayerAction } from "@/types";
import { actionKey, buildPlayerAction, needsText, parseLegalActions } from "./v2Actions";

interface V2ActionPanelProps {
  state: GameState;
  onAction: (action: PlayerAction) => void;
  pending?: boolean;
}

const ABSTAIN = "__abstain__";
const selectClass = "flex h-10 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-60";

export function V2ActionPanel({ state, onAction, pending = false }: V2ActionPanelProps) {
  const { i18n } = useTranslation();
  const id = useId();
  const english = i18n.language.startsWith("en");
  const traditional = /TW|HK|Hant/i.test(i18n.language);
  const words = english
    ? { title: "Your move", action: "Choose an action", target: "Choose a player", say: "What would you like to say?", note: "Add a short reason (optional)", waiting: "Listen to the other players while they take their turns.", sending: "Submitting…", abstain: "Abstain", revealTitle: "Reveal the answer and end this game?", revealBody: "This ends the team's attempt and reveals the full story to everyone.", reveal: "End game and reveal", cancel: "Keep playing", long: "Please shorten your message." }
    : traditional
      ? { title: "你的回合", action: "選擇動作", target: "選擇玩家", say: "說說你的想法", note: "補充簡短理由（可選）", waiting: "先聽聽其他玩家的想法，等待下一次參與機會。", sending: "正在提交…", abstain: "棄票", revealTitle: "結束本局並揭示湯底？", revealBody: "大家的本次解謎將結束，完整故事會向所有玩家揭示。", reveal: "結束並揭示湯底", cancel: "繼續解謎", long: "內容太長，請精簡後提交。" }
      : { title: "你的回合", action: "选择动作", target: "选择玩家", say: "说说你的想法", note: "补充简短理由（可选）", waiting: "先听听其他玩家的想法，等待下一次参与机会。", sending: "正在提交…", abstain: "弃票", revealTitle: "结束本局并揭示汤底？", revealBody: "大家的本次解谜将结束，完整故事会向所有玩家揭示。", reveal: "结束并揭示汤底", cancel: "继续解谜", long: "内容太长，请精简后提交。" };
  const capabilities = parseLegalActions(state.extra?.legalActions);
  const primary = capabilities.filter((a) => needsText(a) || a.targets.length > 0 || a.type === "NIGHT_ACTION");
  const simple = capabilities.filter((a) => !primary.includes(a));
  const [chosenKey, setChosenKey] = useState("");
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [targets, setTargets] = useState<Record<string, string>>({});
  const [revealOpen, setRevealOpen] = useState(false);
  const phaseToken = state.extra?.phaseToken || `${state.phase}:${state.round}`;

  useEffect(() => {
    setChosenKey(""); setDrafts({}); setTargets({}); setRevealOpen(false);
  }, [phaseToken]);

  const selected = primary.find((a) => actionKey(a) === chosenKey) || primary[0];
  const key = selected ? actionKey(selected) : "";
  const content = drafts[key] || "";
  const canAbstain = selected?.type === "VOTE" && capabilities.some((a) => a.type === "SKIP");
  const savedTarget = targets[key] || "";
  const target = selected?.targets.includes(savedTarget) || (canAbstain && savedTarget === ABSTAIN)
    ? savedTarget : selected?.targets.length === 1 ? selected.targets[0] : "";
  const length = Array.from(content.trim()).length;
  const prepared = selected ? buildPlayerAction(selected, { content, target, abstain: target === ABSTAIN }, capabilities) : null;
  const reveal = capabilities.find((a) => a.type === "REVEAL_SOLUTION");
  const dispatch = (action: PlayerAction | null) => { if (action && !pending) onAction(action); };
  const label = (action: LegalAction) => {
    if (!english && !traditional) return action.label;
    const englishNames: Record<string, string> = { SPEAK: "Speak", ASK_PLAYER: "Ask a player", ANSWER_PLAYER: "Answer the question", DISCUSS: "Discuss with teammates", ASK_QUESTION: "Ask the host", SUBMIT_SOLUTION: "Submit the team's answer", VOTE: "Cast vote", HUNTER_SHOOT: "Choose a shooting target", SKIP: "Skip this action", REQUEST_HINT: "Request a hint · costs 1 turn", REVEAL_SOLUTION: "Reveal the answer", WOLF_KILL: "Choose the wolves' target", SEER_CHECK: "Inspect a player", GUARD_PROTECT: "Protect a player", WITCH_SAVE: "Use the antidote", WITCH_POISON: "Use the poison" };
    const traditionalNames: Record<string, string> = { SPEAK: "發言", ASK_PLAYER: "向玩家提問", ANSWER_PLAYER: "回答問題", DISCUSS: "與隊友討論", ASK_QUESTION: "向主持提問", SUBMIT_SOLUTION: "提交共同解答", VOTE: "投票", HUNTER_SHOOT: "選擇開槍目標", SKIP: "跳過這個動作", REQUEST_HINT: "請求提示（消耗1次）", REVEAL_SOLUTION: "揭示湯底", WOLF_KILL: "選擇狼人目標", SEER_CHECK: "查驗玩家", GUARD_PROTECT: "守護玩家", WITCH_SAVE: "使用解藥", WITCH_POISON: "使用毒藥" };
    return (english ? englishNames : traditionalNames)[action.nightAction || action.type] || action.label;
  };

  return (
    <section className="space-y-3" aria-label={words.title} data-testid="v2-action-panel" aria-busy={pending}>
      {capabilities.length === 0 && <p role="status" className="py-2 text-sm leading-6 text-muted-foreground">{words.waiting}</p>}
      {selected && (
        <form className="space-y-3" onSubmit={(event) => { event.preventDefault(); dispatch(prepared); }}>
          {primary.length > 1 ? (
            <div className="space-y-2">
              <Label htmlFor={`${id}-action`}>{words.action}</Label>
              <select id={`${id}-action`} data-testid="v2-action-select" className={selectClass} value={key} onChange={(event) => setChosenKey(event.target.value)} disabled={pending}>
                {primary.map((action) => <option key={actionKey(action)} value={actionKey(action)}>{label(action)}</option>)}
              </select>
            </div>
          ) : <p className="text-sm font-medium">{label(selected)}</p>}
          {selected.targets.length > 0 && (
            <div className="space-y-2">
              <Label htmlFor={`${id}-target`}>{words.target}</Label>
              <select id={`${id}-target`} data-testid="v2-target-select" className={selectClass} value={target} onChange={(event) => setTargets((current) => ({ ...current, [key]: event.target.value }))} disabled={pending}>
                <option value="" disabled>{words.target}</option>
                {selected.targets.map((playerId) => {
                  const player = state.players.find((p) => p.playerId === playerId);
                  return <option key={playerId} value={playerId}>{player ? `${player.seatNumber + 1} · ${player.displayName}` : playerId}</option>;
                })}
                {canAbstain && <option value={ABSTAIN}>{words.abstain}</option>}
              </select>
            </div>
          )}
          {selected.maxLength > 0 && (
            <div className="space-y-2">
              <Label htmlFor={`${id}-text`}>{needsText(selected) ? words.say : words.note}</Label>
              <Textarea id={`${id}-text`} data-testid="v2-action-text" value={content} rows={needsText(selected) ? 4 : 2}
                onChange={(event) => setDrafts((current) => ({ ...current, [key]: event.target.value }))}
                disabled={pending} aria-describedby={`${id}-length`} aria-invalid={length > selected.maxLength}
                className="resize-y leading-6" />
              <div id={`${id}-length`} className={`text-right text-xs ${length > selected.maxLength ? "text-destructive" : "text-muted-foreground"}`}>
                {length > selected.maxLength && <span>{words.long} </span>}{length}/{selected.maxLength}
              </div>
            </div>
          )}
          <Button type="submit" data-testid="v2-action-submit" className="w-full gap-2" disabled={pending || !prepared}>
            {pending ? <LoaderCircle className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden="true" /> : <ArrowRight className="h-4 w-4" aria-hidden="true" />}
            {pending ? words.sending : label(selected)}
          </Button>
        </form>
      )}
      {simple.some((action) => action.type !== "REVEAL_SOLUTION" && !(canAbstain && action.type === "SKIP")) && (
        <div className="flex flex-wrap gap-2 border-t pt-3">
          {simple.filter((action) => action.type !== "REVEAL_SOLUTION" && !(canAbstain && action.type === "SKIP")).map((action) => (
            <Button key={actionKey(action)} type="button" variant="outline" size="sm" disabled={pending}
              data-testid={`v2-action-${action.type.toLowerCase()}`} onClick={() => dispatch(buildPlayerAction(action, {}, capabilities))}>
              {action.type === "REQUEST_HINT" && <HelpCircle className="mr-2 h-3.5 w-3.5" aria-hidden="true" />}{label(action)}
            </Button>
          ))}
        </div>
      )}
      {reveal && (
        <>
          <Button type="button" variant="ghost" size="sm" className="w-full text-muted-foreground" disabled={pending} data-testid="v2-reveal-solution" onClick={() => setRevealOpen(true)}>{label(reveal)}</Button>
          <AlertDialog open={revealOpen} onOpenChange={setRevealOpen}>
            <AlertDialogContent className="motion-reduce:animate-none">
              <AlertDialogHeader><AlertDialogTitle>{words.revealTitle}</AlertDialogTitle><AlertDialogDescription>{words.revealBody}</AlertDialogDescription></AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>{words.cancel}</AlertDialogCancel>
                <AlertDialogAction disabled={pending || !reveal} onClick={() => dispatch(reveal ? buildPlayerAction(reveal, {}, capabilities) : null)}>{words.reveal}</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </>
      )}
    </section>
  );
}
