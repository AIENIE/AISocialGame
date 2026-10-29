import type { GameState } from "@/types";
import { useRoomText, type RoomTextKey } from "./roomText";

export function WerewolfRulesHelp({ rules }: { rules?: Record<string, unknown> }) {
  const copy = useRoomText();
  return <div className="space-y-3 text-sm leading-6" data-testid="werewolf-rules-help">
    <p>{copy("werewolfFlow")}</p>
    {(!rules || rules.winCondition !== "city") && <p>{copy("werewolfWinSide")}</p>}
    {(!rules || rules.winCondition === "city") && <p>{copy("werewolfWinCity")}</p>}
    {rules && <>
      <p>{copy(rules.witchRule === "no_save" ? "werewolfWitchNever" : rules.witchRule === "always_save" ? "werewolfWitchAlways" : "werewolfWitchFirst")}</p>
      <p>{copy(rules.hasLastWords === "none" ? "werewolfWordsNever" : rules.hasLastWords === "always" ? "werewolfWordsAlways" : "werewolfWordsFirst")}</p>
    </>}
    <p>{copy("werewolfVoteRules")}</p>
    <p>{copy("werewolfTimeouts")}</p>
  </div>;
}

export function WerewolfTurnHelp({ state }: { state: GameState }) {
  const copy = useRoomText();
  const hints: Record<string, RoomTextKey> = {
    NIGHT: "werewolfNightWait", DAY_DISCUSS: "werewolfDiscussion", DAY_INTERACTION: "werewolfQuestions",
    DAY_VOTE: "werewolfVoting", DEATH_ACTION: "werewolfDeathAction", LAST_WORDS: "werewolfLastWords",
  };
  const hint = hints[state.phase];
  if (!hint || state.extra?.validationBudgetPaused === true) return null;
  const question = state.extra?.interaction?.question;
  return <div className="room-game-context space-y-1 text-sm leading-6" data-testid="werewolf-turn-help">
    <p>{copy(hint)}</p>
    {state.phase === "DAY_VOTE" && state.extra?.waitingForAi === true && <p role="status">{copy("werewolfAiVotes", { n: Number(state.extra.pendingAiCount || 0) })}</p>}
    {typeof question === "string" && question && <p><span className="text-muted-foreground">{copy("currentQuestion")}：</span>{question}</p>}
  </div>;
}
