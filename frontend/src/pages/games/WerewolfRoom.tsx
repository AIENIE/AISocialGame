import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { CheckSquare, Moon, Play, Sun } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { SettlementPanel } from "@/components/game/SettlementPanel";
import { AiSeatControl } from "./shared/AiSeatControl";
import { GameLogPanel } from "./shared/GameLogPanel";
import { GameRoomFrame } from "./shared/GameRoomFrame";
import { PlayerGrid } from "./shared/PlayerGrid";
import { V2ActionPanel } from "./shared/V2ActionPanel";
import { useRoomRuntime } from "./shared/useRoomRuntime";
import { WEREWOLF_ROLE_LABELS, werewolfBoardRoles } from "./shared/werewolfSetup";

interface NightActionPayload {
  action: string;
  targetPlayerId?: string;
  useHeal?: boolean;
}

const WerewolfRoom = () => {
  const { t } = useTranslation();
  const runtime = useRoomRuntime({ defaultGameId: "werewolf", recoverableMessages: ["你已出局，无法行动"] });
  const {
    gameId,
    room,
    state,
    players,
    alivePlayers,
    currentSpeaker,
    phase,
    personas,
    personaQuery,
    selectedAiId,
    setSelectedAiId,
    canAddAi,
    isHost,
    chatMessages,
    socket,
    showTransition,
    userKey,
    startMutation,
    actionMutation,
    addAiMutation,
    startGame,
    handleActionError,
    invalidateRuntime,
  } = runtime;
  const [speakContent, setSpeakContent] = useState("");
  const [selectedVote, setSelectedVote] = useState<string | null>(null);
  const [nightTarget, setNightTarget] = useState<string | null>(null);

  useEffect(() => {
    if (phase !== "DAY_VOTE") {
      setSelectedVote(null);
    }
  }, [phase]);

  useEffect(() => {
    setNightTarget(null);
  }, [phase, state?.round]);

  const speakMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "SPEAK", content: speakContent || "结束发言" }),
    onSuccess: () => {
      setSpeakContent("");
      invalidateRuntime();
    },
    onError: (error: unknown) => handleActionError(error, "game.speakFailed"),
  });

  const voteMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "VOTE", targetPlayerId: selectedVote || "", abstain: false }),
    onSuccess: invalidateRuntime,
    onError: (error: unknown) => handleActionError(error, "game.voteFailed"),
  });

  const nightMutation = useMutation({
    mutationFn: (payload: NightActionPayload) =>
      actionMutation.mutateAsync({ type: "NIGHT_ACTION", nightAction: payload.action, targetPlayerId: payload.targetPlayerId, useHeal: payload.useHeal }),
    onSuccess: invalidateRuntime,
    onError: (error: unknown) => handleActionError(error, "game.nightFailed"),
  });

  const myRole = state?.myRole;
  const isV2 = state?.extra?.ruleVersion === 2;
  const privateInfo = state?.extra || {};
  const configuredRules = privateInfo.rules || room?.config || {};
  const template = String(configuredRules.template || "standard");
  const playerCount = Number(configuredRules.playerCount || room?.maxPlayers || 12);
  const roleCounts = privateInfo.roleCounts || werewolfBoardRoles(template, playerCount);
  const roleLabel = (role: string) => t(`game.werewolf.role.${role}`, { defaultValue: WEREWOLF_ROLE_LABELS[role] || role });
  const playerName = (id: string) => players.find((player) => player.playerId === id)?.displayName || t("game.werewolf.unknownPlayer", { defaultValue: "玩家" });
  const seerChecks = Array.isArray(privateInfo.seerChecks) ? privateInfo.seerChecks : [];
  const wolfTeam = Array.isArray(privateInfo.wolfTeam) ? privateInfo.wolfTeam as string[] : [];
  const wolfCouncil = Array.isArray(privateInfo.wolfCouncil) ? privateInfo.wolfCouncil : [];
  const phaseLabel = t(`game.phase.werewolf.${phase}.title`, {
    defaultValue: ({ WAITING: "准备中", DAY_INTERACTION: "定向问答", DEATH_ACTION: "离场行动", LAST_WORDS: "遗言" } as Record<string, string>)[phase] || phase,
  });
  const pending = state?.pendingAction;
  const canSpeak = phase === "DAY_DISCUSS" && state?.mySeatNumber === state?.currentSeat;
  const hasVoted = !!(state?.myPlayerId && state?.votes?.[state.myPlayerId]);
  const phaseText = [
    t("game.phaseText.prefix", { phase: isV2 ? phaseLabel : phase }),
    currentSpeaker ? t("game.phaseText.speaker", { name: currentSpeaker.displayName }) : "",
    state?.round ? t("game.phaseText.day", { round: state.round }) : "",
  ]
    .filter(Boolean)
    .join(" • ");
  const selectableNightPlayers = alivePlayers.filter((p) => p.playerId !== state?.myPlayerId);

  return (
    <GameRoomFrame
      connected={socket.connected}
      showReconnectAction={socket.showReconnectAction}
      onReconnect={socket.reconnect}
      gameId={gameId}
      phase={phase}
      showTransition={showTransition}
      tutorialId={`room-${gameId}`}
      tutorialSteps={["game.tutorial.werewolf.0", "game.tutorial.werewolf.1", "game.tutorial.werewolf.2"].map((key) => t(key))}
      title={room?.name || t("game.werewolfTitle")}
      phaseText={phaseText}
      phaseEndsAt={state?.phaseEndsAt}
      aliveCount={alivePlayers.length}
      playerCount={players.length}
      headerExtra={phase === "NIGHT" ? <Moon className="h-4 w-4 text-blue-500" /> : <Sun className="h-4 w-4 text-amber-500" />}
      chatMessages={chatMessages}
      myPlayerId={state?.myPlayerId}
      onSendChat={(type, content) => {
        const sent = socket.sendChat(type, content);
        if (!sent) {
          toast.error(t("game.chat.sendFailed"));
        }
      }}
    >
      {phase === "WAITING" && (
        <Card className="space-y-3 p-4" data-testid="werewolf-rules-preview">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="font-semibold">{t("game.werewolf.rulesPreview", { defaultValue: "本局角色与规则" })}</h2>
            <Badge variant="outline">{room?.seats?.length || 0} / {playerCount}</Badge>
          </div>
          <div className="flex flex-wrap gap-2">
            {Object.entries(roleCounts).map(([role, count]) => (
              <Badge key={role} variant="secondary">{String(count)} × {roleLabel(role)}</Badge>
            ))}
          </div>
          <p className="text-sm text-muted-foreground">
            {t("game.werewolf.rulesPreviewNote", { defaultValue: "6 / 9 人按人数缩编，12 人为完整板。坐满所选人数后开局，AI 可以补位；死亡身份在结算时揭晓。" })}
          </p>
          <div className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
            <span>{t(`create.option.winCondition.${configuredRules.winCondition || "side"}`, { defaultValue: configuredRules.winCondition === "city" ? "屠城：全部好人出局" : "屠边：全部村民或全部神职出局" })}</span>
            <span>{t(`create.option.witchRule.${configuredRules.witchRule || "first_night"}`, { defaultValue: "仅首夜可自救" })}</span>
            <span>{t(`create.option.hasLastWords.${configuredRules.hasLastWords || "first_night"}`, { defaultValue: "仅首夜有遗言" })}</span>
          </div>
          <details className="text-sm">
            <summary className="cursor-pointer text-muted-foreground">{t("game.werewolf.boardDetails", { defaultValue: "查看当前板子的各人数配置" })}</summary>
            <div className="mt-2 overflow-x-auto">
              <table className="w-full text-left text-sm">
                <caption className="sr-only">{t("game.werewolf.boardDetails", { defaultValue: "当前板子的各人数配置" })}</caption>
                <tbody>
                  {[6, 9, 12].map((count) => (
                    <tr key={count} className={count === playerCount ? "bg-muted/50 font-medium" : "text-muted-foreground"}>
                      <th scope="row" className="whitespace-nowrap px-2 py-2">{count} {t("game.werewolf.playersUnit", { defaultValue: "人" })}</th>
                      <td className="px-2 py-2">{Object.entries(werewolfBoardRoles(template, count)).map(([role, total]) => `${total} ${roleLabel(role)}`).join(" · ")}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        </Card>
      )}
      <Card className="p-4">
        <PlayerGrid
          personas={personas}
          players={players}
          myPlayerId={state?.myPlayerId}
          selectedPlayerId={isV2 ? null : selectedVote}
          currentSeat={state?.currentSeat}
          phase={phase}
          votingPhase={isV2 ? "CAPABILITY_VOTE" : "DAY_VOTE"}
          speakingPhase="DAY_DISCUSS"
          onSelectPlayer={setSelectedVote}
          logs={state?.logs}
        />

        <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-3">
          <Card className="p-3">
            <div className="mb-1 text-xs text-muted-foreground">{t("game.myRole")}</div>
            <div className="text-lg font-bold">{myRole ? roleLabel(myRole) : t("game.notDealt")}</div>
            <div className="mt-1 text-xs text-muted-foreground">{t("game.rolePrivate")}</div>
            {isV2 && phase !== "SETTLEMENT" && myRole && (
              <p className="mt-3 text-sm text-muted-foreground">{t(`game.werewolf.roleHelp.${myRole}`, {
                defaultValue: ({
                  WEREWOLF: "回应队友的私密建议，选择今晚的刀口；白天记住自己的公开说法。",
                  SEER: "每夜查验一名其他玩家，记录会保留。你可以选择何时公开自己的查验声明。",
                  WITCH: "整局各一瓶药，每夜最多使用一瓶，也可以留药。解药用完后不再获得刀口信息。",
                  GUARD: "可以自守或跳过，不能连续两夜守同一人。同守同救会失效，守护不防毒和枪。",
                  HUNTER: "被狼刀或放逐后可开一枪，也可放弃；被毒死不能开枪。",
                  IDIOT: "首次被票出时亮身份免死，随后失去投票权；仍可发言，也会被狼刀、毒和枪击杀。",
                  VILLAGER: "结合公开发言与票型判断，主动质询；其他人的身份声明仍需要辨别。",
                } as Record<string, string>)[myRole] || "",
              })}</p>
            )}
          </Card>

          <Card className="p-3">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-medium">{t("game.operation")}</span>
              <Badge variant="outline">{isV2 ? phaseLabel : phase}</Badge>
            </div>
            {phase === "WAITING" && (
              <div className="space-y-2">
                <p className="text-sm text-muted-foreground">{t("game.waitStart")}</p>
                <Button data-testid="game-start-btn" onClick={startGame} disabled={!isHost || startMutation.isPending || (room?.seats?.length || 0) !== playerCount} className="w-full">
                  <Play className="mr-2 h-4 w-4" /> {t("lobby.startGame")}
                </Button>
              </div>
            )}
            {isV2 && state && phase !== "WAITING" && phase !== "SETTLEMENT" && (
              <V2ActionPanel
                state={state}
                pending={actionMutation.isPending}
                onAction={(action) => actionMutation.mutate(action, { onError: (error: unknown) => handleActionError(error, "game.speakFailed") })}
              />
            )}
            {!isV2 && phase === "NIGHT" && pending && (
              <div className="space-y-2">
                <div className="text-sm text-muted-foreground">{pending.description}</div>
                {pending.type === "WITCH" ? (
                  <div className="space-y-2">
                    <div className="flex gap-2">
                      <Button variant="secondary" className="flex-1" onClick={() => nightMutation.mutate({ action: "WITCH_SAVE", useHeal: true })}>
                        {t("game.heal")}
                      </Button>
                      <Button variant="outline" className="flex-1" onClick={() => nightMutation.mutate({ action: "WITCH_SAVE", useHeal: false })}>
                        {t("game.giveUpHeal")}
                      </Button>
                    </div>
                    <Select value={nightTarget || undefined} onValueChange={setNightTarget}>
                      <SelectTrigger>
                        <SelectValue placeholder={t("game.poisonTarget")} />
                      </SelectTrigger>
                      <SelectContent>
                        {selectableNightPlayers.map((p) => (
                          <SelectItem key={p.playerId} value={p.playerId}>
                            {p.displayName}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <Button data-testid="game-night-poison-btn" disabled={!nightTarget} onClick={() => nightTarget && nightMutation.mutate({ action: "WITCH_POISON", targetPlayerId: nightTarget })}>
                      {t("game.poison")}
                    </Button>
                  </div>
                ) : (
                  <>
                    <Select value={nightTarget || undefined} onValueChange={setNightTarget}>
                      <SelectTrigger>
                        <SelectValue placeholder={t("game.selectTarget")} />
                      </SelectTrigger>
                      <SelectContent>
                        {selectableNightPlayers.map((p) => (
                          <SelectItem key={p.playerId} value={p.playerId}>
                            {p.displayName}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <Button data-testid="game-night-submit-btn" disabled={!nightTarget} className="w-full" onClick={() => nightTarget && nightMutation.mutate({ action: pending.type, targetPlayerId: nightTarget })}>
                      {t("game.submitNight")}
                    </Button>
                  </>
                )}
              </div>
            )}
            {!isV2 && canSpeak && (
              <div className="space-y-2">
                <Input data-testid="game-speak-input" value={speakContent} onChange={(e) => setSpeakContent(e.target.value)} placeholder={t("game.speakPlaceholder")} />
                <Button data-testid="game-speak-submit-btn" className="w-full" onClick={() => speakMutation.mutate()} disabled={speakMutation.isPending}>
                  {t("game.endSpeak")}
                </Button>
              </div>
            )}
            {!isV2 && phase === "DAY_VOTE" && (
              <div className="space-y-2">
                <div className="text-xs text-muted-foreground">{t("game.voteHint")}</div>
                <Button data-testid="game-vote-submit-btn" className="w-full" disabled={!selectedVote || hasVoted || voteMutation.isPending} onClick={() => voteMutation.mutate()}>
                  <CheckSquare className="mr-2 h-4 w-4" /> {t("game.vote")}
                </Button>
              </div>
            )}
            {phase === "SETTLEMENT" && state && <SettlementPanel gameId={gameId} state={state} userKey={userKey} />}
          </Card>

          <AiSeatControl
            personas={personas}
            selectedAiId={selectedAiId}
            onSelectedAiIdChange={setSelectedAiId}
            seatCount={room?.seats?.length ?? 0}
            maxPlayers={room?.maxPlayers}
            canAddAi={canAddAi}
            isHost={isHost}
            isWaiting={room?.status === "WAITING"}
            isLoading={personaQuery.isPending}
            isError={personaQuery.isError}
            onRetry={() => void personaQuery.refetch()}
            isAdding={addAiMutation.isPending}
            addError={addAiMutation.isError}
            onAddAi={() => addAiMutation.mutate(selectedAiId)}
          />
        </div>
      </Card>

      {isV2 && phase !== "WAITING" && phase !== "SETTLEMENT" && myRole === "WEREWOLF" && wolfTeam.length > 0 && (
        <Card className="space-y-3 border-indigo-200 p-4 dark:border-indigo-900" data-testid="werewolf-private-council">
          <h2 className="font-semibold">{t("game.werewolf.wolfCouncil", { defaultValue: "狼队私密协作" })}</h2>
          <p className="text-sm">{wolfTeam.map(playerName).join(" · ")}</p>
          {wolfCouncil.map((suggestion, index) => (
            <div key={String(suggestion.eventId || index)} className="rounded-md bg-muted/50 px-3 py-2 text-sm">
              <div className="font-medium">{playerName(String(suggestion.actorId))} → {suggestion.targetPlayerId ? playerName(String(suggestion.targetPlayerId)) : t("game.werewolf.noKill", { defaultValue: "建议空刀" })}</div>
              {suggestion.content && <p className="mt-1 break-words text-muted-foreground">{String(suggestion.content)}</p>}
            </div>
          ))}
          {wolfCouncil.length === 0 && <p className="text-sm text-muted-foreground">{t("game.werewolf.councilEmpty", { defaultValue: "本夜还没有队友提出建议。轮到你时，可以将建议与选刀一起提交。" })}</p>}
        </Card>
      )}

      {isV2 && phase !== "WAITING" && phase !== "SETTLEMENT" && myRole === "SEER" && (
        <Card className="space-y-3 p-4" data-testid="werewolf-private-checks">
          <h2 className="font-semibold">{t("game.werewolf.seerHistory", { defaultValue: "我的查验记录" })}</h2>
          {seerChecks.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead><tr className="text-muted-foreground"><th className="pb-2">{t("game.werewolf.night", { defaultValue: "夜晚" })}</th><th className="pb-2">{t("game.werewolf.checkedPlayer", { defaultValue: "查验对象" })}</th><th className="pb-2">{t("game.werewolf.checkResult", { defaultValue: "结果" })}</th></tr></thead>
                <tbody>{seerChecks.map((check, index) => (
                  <tr key={String(check.eventId || `${check.round}-${index}`)} className="border-t">
                    <td className="py-2">{String(check.round)}</td>
                    <td className="py-2">{playerName(String(check.targetPlayerId))}</td>
                    <td className={`py-2 font-medium ${check.result === "WOLF" ? "text-rose-600 dark:text-rose-400" : "text-emerald-700 dark:text-emerald-400"}`}>{check.result === "WOLF" ? roleLabel("WEREWOLF") : t("game.werewolf.good", { defaultValue: "好人" })}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          ) : <p className="text-sm text-muted-foreground">{t("game.werewolf.noChecks", { defaultValue: "尚无查验记录；结果只对你可见。" })}</p>}
        </Card>
      )}

      {isV2 && phase !== "WAITING" && phase !== "SETTLEMENT" && myRole === "WITCH" && (
        <Card className="space-y-3 p-4" data-testid="werewolf-private-potions">
          <h2 className="font-semibold">{t("game.werewolf.myPotions", { defaultValue: "我的药量" })}</h2>
          <div className="flex flex-wrap gap-3 text-sm">
            <Badge variant="outline">{t("game.werewolf.antidote", { defaultValue: "解药" })} × {Number(privateInfo.antidoteRemaining || 0)}</Badge>
            <Badge variant="outline">{t("game.werewolf.poison", { defaultValue: "毒药" })} × {Number(privateInfo.poisonRemaining || 0)}</Badge>
          </div>
          {phase === "NIGHT" && Object.prototype.hasOwnProperty.call(privateInfo, "wolfTarget") && (
            <p className="text-sm">{privateInfo.wolfTarget ? t("game.werewolf.knifeTarget", { defaultValue: "今晚的刀口：{{name}}", name: playerName(String(privateInfo.wolfTarget)) }) : t("game.werewolf.noKnife", { defaultValue: "今晚没有刀口。" })}</p>
          )}
        </Card>
      )}

      {isV2 && phase !== "WAITING" && phase !== "SETTLEMENT" && myRole === "GUARD" && (
        <Card className="space-y-2 p-4" data-testid="werewolf-private-guard">
          <h2 className="font-semibold">{t("game.werewolf.myGuardHistory", { defaultValue: "我的守护记录" })}</h2>
          <p className="text-sm text-muted-foreground">{privateInfo.lastGuardTarget ? t("game.werewolf.lastGuardTarget", { defaultValue: "上一夜守护：{{name}}。本夜不能连续守护同一人。", name: playerName(String(privateInfo.lastGuardTarget)) }) : t("game.werewolf.noPreviousGuard", { defaultValue: "上一夜没有守护目标，可以从当前合法目标中选择。" })}</p>
        </Card>
      )}

      <GameLogPanel gameId={gameId} roomId={room?.id} archiveId={state?.extra?.archiveId} viewVersion={state?.extra?.viewVersion} logs={state?.logs} emptyText={t("game.logEmpty.werewolf")} />
    </GameRoomFrame>
  );
};

export default WerewolfRoom;
