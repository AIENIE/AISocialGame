import type { ReactNode } from "react";
import { Check, LoaderCircle, Send, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import type { GameState } from "@/types";
import { actionKey, buildPlayerAction, needsText } from "./v2Actions";
import { actionLabel } from "./roomActions";
import { useRoomActionController } from "./useRoomActionController";
import { useRoomText } from "./roomText";

type Controller = ReturnType<typeof useRoomActionController>;
export function RoomActionDock({ state, control, connected, waitingText, onChoosePlayer, context }: {
  state: GameState; control: Controller; connected: boolean; waitingText: string; onChoosePlayer: () => void; context?: ReactNode;
}) {
  const copy = useRoomText();
  const { selected, primary, key, draft, target, prepared, abstain, busy, capabilities, submitted } = control;
  const disabled = busy || !connected;
  const player = state.players.find(p => p.playerId === target);
  const label = selected ? actionLabel(selected, copy) : "";
  const targetName = player ? `${copy("seat", { n: player.seatNumber + 1 })} ${player.displayName}` : "";
  const buttonText = selected?.type === "SPEAK" ? copy("send") : target === "__abstain__" ? copy("abstain") : targetName ? copy("confirmTarget", { action: label, name: targetName }) : label;
  const send = () => {
    if (disabled || !prepared) return;
    if (selected?.type === "SUBMIT_SOLUTION" && state.phase === "FINAL_ANSWER") control.setConfirmation(prepared);
    else void control.submit(prepared);
  };
  return <section className={`room-action-dock ${capabilities.length ? "room-action-active" : ""}`} data-testid="v2-action-panel" aria-label={copy("yourTurn")} aria-busy={busy}>
    {control.changed && <p className="room-turn-notice" role="status">{copy("phaseChanged")}</p>}
    {!connected && <p className="text-sm text-amber-700 dark:text-amber-300" role="status">{copy("reconnect")}</p>}
    {submitted && <p className="flex items-center gap-1 text-sm text-emerald-700 dark:text-emerald-300" role="status"><Check className="h-4 w-4" />{copy("submitted")}</p>}
    {!capabilities.length && !submitted && <p className="py-2 text-sm text-muted-foreground" role="status">{waitingText}</p>}
    {context}
    {primary.length > 1 && <div className="room-action-modes" role="group" aria-label={copy("yourTurn")}>
      {primary.map(action => <Button key={actionKey(action)} size="sm" variant={key === actionKey(action) ? "secondary" : "ghost"} aria-pressed={key === actionKey(action)} disabled={disabled} onClick={() => control.choose(actionKey(action))}>{actionLabel(action, copy)}</Button>)}
    </div>}
    {selected && <form onSubmit={event => { event.preventDefault(); send(); }} className="space-y-2">
      {selected.targets.length > 0 && <div className="flex min-w-0 items-center gap-2">
        <Button type="button" variant="outline" size="sm" onClick={onChoosePlayer} disabled={disabled} data-testid="room-choose-target" className="min-w-0"><Users className="mr-1 h-4 w-4 shrink-0" /><span className="truncate">{targetName ? copy("target", { name: targetName }) : copy("choose")}</span></Button>
        {abstain && <Button type="button" size="sm" variant={target === "__abstain__" ? "secondary" : "ghost"} aria-pressed={target === "__abstain__"} disabled={disabled} onClick={() => control.selectTarget("__abstain__")}>{copy("abstain")}</Button>}
      </div>}
      {selected.maxLength > 0 && <Textarea data-testid="v2-action-text" aria-label={label} value={draft} rows={2} className="room-composer" disabled={disabled}
        placeholder={copy(needsText(selected) ? "draft" : "note")} aria-invalid={Array.from(draft.trim()).length > selected.maxLength}
        onChange={event => control.setDraft(event.target.value)} />}
      <div className="room-compose-footer">
        <div className="min-w-0 text-xs text-muted-foreground">
          {state.gameId === "turtle_soup" && <span>{copy(selected.type === "DISCUSS" ? "freeDiscuss" : state.phase === "FINAL_ANSWER" ? "finalWarning" : "cost")}</span>}
          {selected.maxLength > 0 && <span className={`ml-2 tabular-nums ${Array.from(draft.trim()).length > selected.maxLength ? "text-destructive" : ""}`}>{Array.from(draft.trim()).length}/{selected.maxLength}</span>}
        </div>
        <Button type="submit" data-testid="v2-action-submit" disabled={disabled || !prepared} className="room-submit">
          {busy ? <LoaderCircle className="mr-1 h-4 w-4 animate-spin motion-reduce:animate-none" /> : <Send className="mr-1 h-4 w-4 shrink-0" />}<span className="truncate">{busy ? copy("sending") : buttonText}</span>
        </Button>
      </div>
    </form>}
    {capabilities.filter(a => !primary.includes(a) && !(abstain && a.type === "SKIP")).length > 0 && <div className="flex flex-wrap gap-1">
      {capabilities.filter(a => !primary.includes(a) && !(abstain && a.type === "SKIP")).map(action => <Button key={actionKey(action)} type="button" variant="ghost" size="sm" disabled={disabled}
        data-testid={`v2-action-${action.type.toLowerCase()}`} onClick={() => {
          const next = buildPlayerAction(action, {}, capabilities);
          if (action.type === "REVEAL_SOLUTION") control.setConfirmation(next);
          else if (Number(state.extra?.ruleVersion) !== 2 && state.phase === "NIGHT" && action.type === "SKIP") void control.submit({ type: "NIGHT_ACTION", nightAction: "WITCH_SAVE", useHeal: false });
          else void control.submit(next);
        }}>{action.type === "SKIP" && ["VOTING", "DAY_VOTE", "RUNOFF"].includes(state.phase) ? copy("abstain") : actionLabel(action, copy)}{action.type === "REQUEST_HINT" && <span className="ml-1 text-xs text-muted-foreground">· {copy("hintCost")}</span>}</Button>)}
    </div>}
    <AlertDialog open={!!control.confirmation} onOpenChange={open => { if (!open) control.setConfirmation(null); }}>
      <AlertDialogContent><AlertDialogHeader><AlertDialogTitle>{copy(control.confirmation?.type === "REVEAL_SOLUTION" ? "reveal" : "solution")}</AlertDialogTitle>
        <AlertDialogDescription>{copy(control.confirmation?.type === "REVEAL_SOLUTION" ? "revealWarning" : "finalWarning")}</AlertDialogDescription></AlertDialogHeader>
        {control.confirmation?.content && <p className="whitespace-pre-wrap break-words text-sm">{control.confirmation.content}</p>}
        <AlertDialogFooter><AlertDialogCancel>{copy("cancel")}</AlertDialogCancel><AlertDialogAction disabled={disabled} onClick={event => { event.preventDefault(); if (!disabled) void control.submit(control.confirmation); }}>{copy("confirm")}</AlertDialogAction></AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  </section>;
}
