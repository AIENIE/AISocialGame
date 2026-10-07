import { useTranslation } from "react-i18next";
import type { GameState, Room } from "@/types";
import { useRoomText } from "./roomText";
import { roleName } from "./roomIdentity";
import { werewolfBoardRoles } from "./werewolfSetup";

export function RoomRules({ state, room }: { state: GameState; room: Room }) {
  const { t, i18n } = useTranslation();
  const copy = useRoomText();
  const rules = state.extra?.rules || room.config || {};
  const counts = state.extra?.roleCounts || werewolfBoardRoles(String(rules.template || "standard"), Number(rules.playerCount || room.maxPlayers));
  return <div className="space-y-3 text-sm leading-6" data-testid={`${state.gameId}-rules-preview`}>
    {state.gameId === "undercover" ? <><p>{copy("undercoverRules")}</p>{(rules.hasBlank || state.extra?.hasBlank) && <p>{copy("blankRules")}</p>}{rules.hostMode === "AUTHOR" && <p>{copy("authorRules")}</p>}</> : state.gameId === "turtle_soup" ? <p>{copy("soupRules")}</p> : <>
      <div className="flex flex-wrap gap-2">{Object.entries(counts).map(([role, count]) => <span key={role} className="rounded-md bg-muted px-2 py-1">{String(count)} × {roleName(role, i18n.language)}</span>)}</div>
      <p>{t("game.werewolf.rulesPreviewNote")}</p>
      <p>{t(`create.option.winCondition.${rules.winCondition || "side"}`)} · {t(`create.option.witchRule.${rules.witchRule || "first_night"}`)} · {t(`create.option.hasLastWords.${rules.hasLastWords || "first_night"}`)}</p>
    </>}
  </div>;
}

export function PrivateDetails({ state }: { state: GameState }) {
  const { t, i18n } = useTranslation();
  const copy = useRoomText(), extra = state.extra || {};
  const name = (id: unknown) => state.players.find(p => p.playerId === id)?.displayName || copy("none");
  return <div className="space-y-4 text-sm leading-6">
    {state.gameId !== "turtle_soup" && <p className="text-xs text-muted-foreground">{copy("private")}</p>}
    {state.gameId === "undercover" && <p>{copy(state.myRole === "BLANK" || extra.blank ? "blankRules" : "wordNote")}</p>}
    {state.gameId === "turtle_soup" && <><h3 className="font-semibold">{copy("clues")}</h3><ul className="space-y-2">{(Array.isArray(extra.knownClues) ? extra.knownClues : []).map((clue: string) => <li key={clue}>{clue}</li>)}</ul></>}
    {state.gameId === "werewolf" && state.myRole && <p>{t(`game.werewolf.roleHelp.${state.myRole}`, { defaultValue: roleName(state.myRole, i18n.language) })}</p>}
    {state.myRole === "WEREWOLF" && <section data-testid="werewolf-private-council"><h3 className="font-semibold">{copy("team")}</h3><p>{(extra.wolfTeam || []).map(name).join(" · ")}</p>
      {(extra.wolfCouncil || []).length ? (extra.wolfCouncil as { actorId: string; targetPlayerId?: string; content?: string; eventId?: string }[]).map((item, index) => <div key={item.eventId || index} className="mt-3 border-l-2 pl-3"><b>{name(item.actorId)} → {name(item.targetPlayerId)}</b><p>{item.content}</p></div>) : <p className="text-muted-foreground">{copy("councilEmpty")}</p>}
    </section>}
    {state.myRole === "SEER" && <section data-testid="werewolf-private-checks"><h3 className="font-semibold">{t("game.werewolf.seerHistory")}</h3>{(extra.seerChecks || []).length ? (extra.seerChecks as { eventId?: string; round: number; targetPlayerId: string; result: string }[]).map((check, index) => <p key={check.eventId || index}>{copy("round", { n: check.round })} · {name(check.targetPlayerId)} · <b>{check.result === "WOLF" ? roleName("WEREWOLF", i18n.language) : copy("good")}</b></p>) : <p>{copy("noChecks")}</p>}</section>}
    <PrivateActionContext state={state} full />
  </div>;
}

export function PrivateActionContext({ state, full = false }: { state: GameState; full?: boolean }) {
  const copy = useRoomText(), extra = state.extra || {};
  const name = (id: unknown) => state.players.find(p => p.playerId === id)?.displayName || copy("none");
  if (state.myRole === "WITCH") return <div className="room-private-context" data-testid="werewolf-private-potions"><span>{copy("private")}</span><span>{copy("heal")} × {Number(extra.antidoteRemaining || 0)}</span><span>{copy("poison")} × {Number(extra.poisonRemaining || 0)}</span>{Object.prototype.hasOwnProperty.call(extra, "wolfTarget") && <b>{copy("nightTarget", { name: name(extra.wolfTarget) })}</b>}</div>;
  if (state.myRole === "GUARD") return <p className="room-private-context" data-testid="werewolf-private-guard">{copy("private")} · {copy("lastGuard", { name: name(extra.lastGuardTarget) })}</p>;
  if (state.myRole === "WEREWOLF" && !full) return <div className="room-private-context"><span>{copy("team")}</span>{(extra.wolfCouncil || []).slice(-2).map((item: { actorId: string; targetPlayerId?: string; content?: string }, index: number) => <span key={index}>{name(item.actorId)} → {name(item.targetPlayerId)}{item.content ? ` · ${item.content}` : ""}</span>)}</div>;
  return null;
}

export function GameContext({ state }: { state: GameState }) {
  const copy = useRoomText();
  const extra = state.extra || {};
  if (state.gameId === "turtle_soup" && extra.surface) return <div className="room-game-context">
    <details><summary><span className="font-medium">{copy("surface")} · {String(extra.caseTitle || "")}</span><span className="text-xs text-muted-foreground">{copy("budget", { n: extra.questionCount || 0, max: extra.maxQuestions || 0 })} · {copy("hintBudget", { n: extra.hintCount || 0, max: extra.maxHints ?? 2 })}</span></summary>
      <p className="max-h-40 overflow-auto whitespace-pre-wrap py-2 text-sm leading-6">{String(extra.surface)}</p></details>
    {extra.hostThinking && <p className="mt-1 text-xs text-muted-foreground" role="status">{copy("thinking")}</p>}
    {state.phase === "FINAL_ANSWER" && <p className="mt-1 text-xs text-amber-700 dark:text-amber-300">{copy("finalWarning")}</p>}
  </div>;
  const question = extra.activeQuestion?.content || extra.interaction?.question;
  if (typeof question === "string" && question) return <div className="room-game-context"><p className="text-xs text-muted-foreground">{copy("currentQuestion")}</p><p className="text-sm leading-6">{question}</p></div>;
  return null;
}
