import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { CheckSquare, Play, Send } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { SettlementPanel } from "@/components/game/SettlementPanel";
import { AiSeatControl } from "./shared/AiSeatControl";
import { GameLogPanel } from "./shared/GameLogPanel";
import { GameRoomFrame } from "./shared/GameRoomFrame";
import { PlayerGrid } from "./shared/PlayerGrid";
import { useRoomRuntime } from "./shared/useRoomRuntime";
import { V2ActionPanel } from "./shared/V2ActionPanel";

const UndercoverRoom = () => {
  const { t, i18n } = useTranslation();
  const runtime = useRoomRuntime({ defaultGameId: "undercover" });
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
  const isV2 = Number(state?.extra?.ruleVersion) === 2;
  const authorRoom = room?.config?.hostMode === "AUTHOR";
  const authorHost = isHost && authorRoom;
  const blank = state?.myRole === "BLANK" || state?.extra?.blank === true;
  const copy = i18n.language.startsWith("en")
    ? { blank: "Blank · no word", blankHint: "Infer from public descriptions. Survive among the final two players to win alone.", hidden: "You know your word, but your faction is yours to figure out.", host: "Question author · host", hostHint: "You host this custom game without taking a player seat.", custom: "Custom word game: the author hosts and does not play. This casual game does not count toward rankings.", rules: "How this round works", ruleHint: "Describe in turn, then up to two rotating players can ask one question each. The named player gets one reply. A tied vote grants one defense before a runoff; a second tie eliminates nobody. Three rounds without elimination end in a draw.", question: "Current question", candidates: "Runoff candidates", ballots: "Last revealed ballot", abstain: "Abstain", submitted: "Votes submitted", noElimination: "Consecutive rounds without elimination", wordReveal: "Words revealed", civilian: "Majority word", undercover: "Minority word" }
    : /TW|HK|Hant/i.test(i18n.language)
      ? { blank: "白板 · 沒有詞語", blankHint: "依公開描述推測，存活到最後兩人時獨自獲勝。", hidden: "你只知道自己的詞語，陣營要根據大家的發言判斷。", host: "出題主持", hostHint: "你負責主持本局自訂詞庫，不佔玩家席位，也不參加投票。", custom: "本局使用自訂詞庫；出題者只主持、不參賽，休閒對局不計入排行榜。", rules: "本輪怎麼玩", ruleHint: "依序描述後，兩名輪替的玩家各有一次質疑機會，被點名者各回應一次。最高票並列者各辯解一次再複投，仍平票則無人出局；連續三輪無人出局則平局。", question: "正在回應的問題", candidates: "複投候選人", ballots: "最近一次公開票型", abstain: "棄票", submitted: "已提交投票", noElimination: "連續無人出局輪數", wordReveal: "詞語揭曉", civilian: "多數方詞語", undercover: "少數方詞語" }
      : { blank: "白板 · 没有词语", blankHint: "依公开描述推测，存活到最后两人时独自获胜。", hidden: "你只知道自己的词语，阵营要根据大家的发言判断。", host: "出题主持", hostHint: "你负责主持本局自定义词库，不占玩家席位，也不参加投票。", custom: "本局使用自定义词库；出题者只主持、不参赛，休闲对局不计入排行榜。", rules: "本轮怎么玩", ruleHint: "依序描述后，两名轮换的玩家各有一次质疑机会，被点名者各回应一次。最高票并列者各辩解一次再复投，仍平票则无人出局；连续三轮无人出局则平局。", question: "正在回应的问题", candidates: "复投候选人", ballots: "最近一次公开票型", abstain: "弃票", submitted: "已提交投票", noElimination: "连续无人出局轮数", wordReveal: "词语揭晓", civilian: "多数方词语", undercover: "少数方词语" };
  const english = i18n.language.startsWith("en");
  const traditional = /TW|HK|Hant/i.test(i18n.language);
  const phaseNames: Record<string, string> = english
    ? { DESCRIPTION: "Descriptions", CHALLENGE: "Question", RESPONSE: "Response", VOTING: "Voting", TIE_DEFENSE: "Tied defense", RUNOFF: "Runoff", SETTLEMENT: "Settlement" }
    : traditional
      ? { DESCRIPTION: "描述", CHALLENGE: "質疑", RESPONSE: "回應", VOTING: "投票", TIE_DEFENSE: "平票辯解", RUNOFF: "複投", SETTLEMENT: "結算" }
      : { DESCRIPTION: "描述", CHALLENGE: "质疑", RESPONSE: "回应", VOTING: "投票", TIE_DEFENSE: "平票辩解", RUNOFF: "复投", SETTLEMENT: "结算" };
  const phaseLabel = isV2 ? phaseNames[phase] || phase : phase;
  const question = state?.extra?.activeQuestion;
  const runoffCandidates: string[] = Array.isArray(state?.extra?.runoffCandidates) ? state.extra.runoffCandidates : [];
  const revealedVotes = state?.extra?.lastVoteResult?.votes as Record<string, string> | undefined;
  const playerName = (id: string) => players.find((p) => p.playerId === id)?.displayName || id;

  useEffect(() => {
    if (phase !== "VOTING") {
      setSelectedVote(null);
    }
  }, [phase]);

  const speakMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "SPEAK", content: speakContent || "我已描述完毕" }),
    onSuccess: () => {
      setSpeakContent("");
      invalidateRuntime();
    },
    onError: (error: unknown) => handleActionError(error, "game.submitSpeakFailed"),
  });

  const voteMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "VOTE", targetPlayerId: selectedVote || "", abstain: false }),
    onSuccess: invalidateRuntime,
    onError: (error: unknown) => handleActionError(error, "game.voteFailed"),
  });

  const canSpeak = phase === "DESCRIPTION" && state?.mySeatNumber === state?.currentSeat;
  const hasVoted = !!(state?.myPlayerId && state?.votes?.[state.myPlayerId]);
  const canVote = phase === "VOTING" && !!selectedVote && !hasVoted;
  const phaseText = [
    t("game.phaseText.prefix", { phase: phaseLabel }),
    currentSpeaker ? t("game.phaseText.speaker", { name: currentSpeaker.displayName }) : "",
    state?.round ? t("game.phaseText.round", { round: state.round }) : "",
  ]
    .filter(Boolean)
    .join(" • ");

  return (
    <GameRoomFrame
      connected={socket.connected}
      showReconnectAction={socket.showReconnectAction}
      onReconnect={socket.reconnect}
      gameId={gameId}
      phase={phase}
      showTransition={showTransition}
      tutorialId={`room-${gameId}`}
      tutorialSteps={["game.tutorial.undercover.0", "game.tutorial.undercover.1", "game.tutorial.undercover.2"].map((key) => t(key))}
      title={room?.name || t("game.undercoverTitle")}
      phaseText={phaseText}
      phaseEndsAt={state?.phaseEndsAt}
      aliveCount={alivePlayers.length}
      playerCount={players.length}
      chatMessages={chatMessages}
      myPlayerId={state?.myPlayerId}
      onSendChat={(type, content) => {
        const sent = socket.sendChat(type, content);
        if (!sent) {
          toast.error(t("game.chat.sendFailed"));
        }
      }}
    >
      <Card className="p-4">
        <PlayerGrid
          personas={personas}
          players={players}
          logs={state?.logs}
          myPlayerId={state?.myPlayerId}
          selectedPlayerId={selectedVote}
          currentSeat={state?.currentSeat}
          phase={phase}
          votingPhase={isV2 ? "__V2_ACTIONS__" : "VOTING"}
          speakingPhase={isV2 ? phase : "DESCRIPTION"}
          onSelectPlayer={setSelectedVote}
        />

        <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-3">
          <Card className="border-dashed p-3">
            <div className="mb-2 text-xs text-muted-foreground">{t("game.myWord")}</div>
            <div className="text-lg font-bold" data-testid="undercover-private-word">{authorHost ? copy.host : blank ? copy.blank : state?.myWord || t("game.waitingDeal")}</div>
            <div className="mt-1 text-xs leading-5 text-muted-foreground">{authorHost ? copy.hostHint : blank ? copy.blankHint : isV2 ? copy.hidden : state?.myRole === "UNDERCOVER" ? t("game.wordUndercover") : t("game.wordHint")}</div>
          </Card>

          <Card className="p-3">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-medium">{t("game.operation")}</span>
              <Badge variant="outline">{phaseLabel}</Badge>
            </div>
            {phase === "WAITING" && (
              <div className="space-y-2">
                <p className="text-sm text-muted-foreground">{t("game.waitStart")}</p>
                <Button data-testid="game-start-btn" onClick={startGame} disabled={!isHost || startMutation.isPending} className="w-full">
                  <Play className="mr-2 h-4 w-4" /> {t("lobby.startGame")}
                </Button>
              </div>
            )}
            {isV2 && state && phase !== "WAITING" && phase !== "SETTLEMENT" && !authorHost && (
              <V2ActionPanel state={state} pending={actionMutation.isPending}
                onAction={(action) => actionMutation.mutate(action, { onError: (error: unknown) => handleActionError(error, "game.submitSpeakFailed") })} />
            )}
            {!isV2 && canSpeak && (
              <div className="space-y-2">
                <Input data-testid="game-speak-input" value={speakContent} onChange={(e) => setSpeakContent(e.target.value)} placeholder={t("game.descriptionPlaceholder")} />
                <Button data-testid="game-speak-submit-btn" className="w-full" onClick={() => speakMutation.mutate()} disabled={speakMutation.isPending}>
                  <Send className="mr-2 h-4 w-4" /> {t("game.submitSpeak")}
                </Button>
              </div>
            )}
            {!isV2 && phase === "VOTING" && (
              <div className="space-y-2">
                <div className="text-xs text-muted-foreground">{t("game.voteHint")}</div>
                <Button data-testid="game-vote-submit-btn" className="w-full" disabled={!canVote || voteMutation.isPending} onClick={() => voteMutation.mutate()}>
                  <CheckSquare className="mr-2 h-4 w-4" /> {t("game.vote")}
                </Button>
              </div>
            )}
            {phase === "SETTLEMENT" && state && <SettlementPanel gameId={gameId} state={state} userKey={userKey} />}
            {!isV2 && !canSpeak && phase === "DESCRIPTION" && (
              <div className="text-sm text-muted-foreground">{t("game.waitingSpeaker", { name: currentSpeaker?.displayName || t("game.player") })}</div>
            )}
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

      {authorRoom && <p className="rounded-lg border border-dashed p-3 text-sm leading-6 text-muted-foreground" role="note">{copy.custom}</p>}
      {isV2 && state && (
        <Card className="space-y-4 p-4" data-testid="undercover-round-information">
          {typeof question?.content === "string" && question.content && (
            <div className="border-l-2 border-cyan-500 pl-3" role="status">
              <p className="text-xs text-muted-foreground">{copy.question} · {playerName(String(question.actorId))} → {playerName(String(question.targetId))}</p>
              <p className="mt-1 text-sm leading-6">{question.content}</p>
            </div>
          )}
          {runoffCandidates.length > 0 && ["TIE_DEFENSE", "RUNOFF"].includes(phase) && <p className="text-sm">{copy.candidates}：{runoffCandidates.map(playerName).join(" · ")}</p>}
          {["VOTING", "RUNOFF"].includes(phase) && <p className="text-sm" role="status">{copy.submitted}：{Array.isArray(state.extra?.votedPlayers) ? state.extra.votedPlayers.length : 0}/{alivePlayers.length}</p>}
          {Number(state.extra?.noEliminationRounds) > 0 && <p className="text-xs text-muted-foreground">{copy.noElimination}：{state.extra?.noEliminationRounds}/3</p>}
          {revealedVotes && Object.keys(revealedVotes).length > 0 && (
            <details className="text-sm">
              <summary className="cursor-pointer font-medium">{copy.ballots}</summary>
              <ul className="mt-2 grid gap-1 text-muted-foreground sm:grid-cols-2">
                {Object.entries(revealedVotes).map(([voter, target]) => <li key={voter}>{playerName(voter)} → {target === "abstain" ? copy.abstain : playerName(target)}</li>)}
              </ul>
            </details>
          )}
          {phase === "SETTLEMENT" && (
            <div className="space-y-1 text-sm" data-testid="undercover-word-reveal">
              <p className="font-medium">{copy.wordReveal}</p>
              <p>{copy.civilian}：{String(state.extra?.civilianWord || "")}</p>
              <p>{copy.undercover}：{String(state.extra?.undercoverWord || "")}</p>
            </div>
          )}
          <details className="text-sm">
            <summary className="cursor-pointer font-medium">{copy.rules}</summary>
            <p className="mt-2 leading-6 text-muted-foreground">{copy.ruleHint}</p>
            {state.extra?.hasBlank && <p className="mt-1 leading-6 text-muted-foreground">{copy.blankHint}</p>}
          </details>
        </Card>
      )}

      <GameLogPanel gameId={gameId} roomId={room?.id} archiveId={state?.extra?.archiveId} viewVersion={state?.extra?.viewVersion} logs={state?.logs} emptyText={t("game.logEmpty.undercover")} />
    </GameRoomFrame>
  );
};

export default UndercoverRoom;
