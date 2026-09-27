import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { BookOpen, HelpCircle, Lightbulb, LoaderCircle, Play, Send, Trophy } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { SettlementPanel } from "@/components/game/SettlementPanel";
import { AiSeatControl } from "./shared/AiSeatControl";
import { GameLogPanel } from "./shared/GameLogPanel";
import { GameRoomFrame } from "./shared/GameRoomFrame";
import { PlayerGrid } from "./shared/PlayerGrid";
import { useRoomRuntime } from "./shared/useRoomRuntime";
import { V2ActionPanel } from "./shared/V2ActionPanel";

type QaItem = {
  id?: string;
  actorId?: string;
  displayName?: string;
  question: string;
  answer: string;
  clues?: string[];
  aiGenerated?: boolean;
  time?: string;
  duplicate?: boolean;
};

const stringList = (value: unknown): string[] => {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
};

const qaList = (value: unknown): QaItem[] => {
  return Array.isArray(value)
    ? value
        .filter((item): item is Record<string, unknown> => item !== null && typeof item === "object")
        .map((item) => ({
          id: typeof item.id === "string" ? item.id : undefined,
          actorId: typeof item.actorId === "string" ? item.actorId : undefined,
          displayName: typeof item.displayName === "string" ? item.displayName : undefined,
          question: typeof item.question === "string" ? item.question : "",
          answer: typeof item.answer === "string" ? item.answer : "",
          clues: stringList(item.clues),
          aiGenerated: Boolean(item.aiGenerated),
          time: typeof item.time === "string" ? item.time : undefined,
          duplicate: Boolean(item.duplicate),
        }))
    : [];
};

const TurtleSoupRoom = () => {
  const { t, i18n } = useTranslation();
  const runtime = useRoomRuntime({ defaultGameId: "turtle_soup" });
  const {
    gameId,
    room,
    state,
    players,
    alivePlayers,
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
  const [question, setQuestion] = useState("");
  const [solution, setSolution] = useState("");

  const extra = state?.extra || {};
  const v2 = Number(extra.ruleVersion) === 2;
  const english = i18n.language.startsWith("en");
  const traditional = /TW|HK|Hant/i.test(i18n.language);
  const labels = english
    ? { thinking: "The host is considering the question…", final: "One final answer", finalHelp: "Keep discussing together, then have a human player submit the team's final answer. An incorrect final answer ends the game.", budget: "Exploration turns", hint: "Hints used", duplicate: "Already answered · no turn spent", confirmed: "Host-confirmed questions", waiting: "Questions are counted after the host has confirmed an answer." }
    : traditional
      ? { thinking: "主持正在確認這個問題…", final: "最後一次共同解答", finalHelp: "大家仍可繼續討論，再由真人確認並提交最後一次解答；答錯將揭示湯底並結算。", budget: "提問／試答次數", hint: "已用提示", duplicate: "已確認過 · 不重複計次", confirmed: "主持已確認的問答", waiting: "主持確認有效答覆後才會計次。" }
      : { thinking: "主持正在确认这个问题…", final: "最后一次共同解答", finalHelp: "大家仍可继续讨论，再由真人确认并提交最后一次解答；答错将揭示汤底并结算。", budget: "提问／试答次数", hint: "已用提示", duplicate: "已确认过 · 不重复计次", confirmed: "主持已确认的问答", waiting: "主持确认有效答复后才会计次。" };
  const knownClues = stringList(extra.knownClues);
  const qaHistory = qaList(extra.qaHistory);
  const questionCount = Number(extra.questionCount || 0);
  const maxQuestions = Number(extra.maxQuestions || 0);
  const surface = typeof extra.surface === "string" ? extra.surface : "";
  const caseTitle = typeof extra.caseTitle === "string" ? extra.caseTitle : t("games.turtle_soup.name");
  const hostVerdict = typeof extra.hostVerdict === "string" ? extra.hostVerdict : "";
  const revealedSolution = typeof extra.solution === "string" ? extra.solution : "";

  const askMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "ASK_QUESTION", content: question.trim() }),
    onSuccess: () => {
      setQuestion("");
      invalidateRuntime();
    },
    onError: (error: unknown) => handleActionError(error, "game.askFailed"),
  });

  const solutionMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "SUBMIT_SOLUTION", content: solution.trim() }),
    onSuccess: () => {
      invalidateRuntime();
    },
    onError: (error: unknown) => handleActionError(error, "game.solutionFailed"),
  });

  const phaseText = [
    t("game.phaseText.prefix", { phase }),
    state?.round ? t("game.phaseText.game", { round: state.round }) : "",
    maxQuestions ? (v2 ? `${labels.budget} ${questionCount}/${maxQuestions}` : t("game.phaseText.questions", { current: questionCount, max: maxQuestions })) : "",
  ]
    .filter(Boolean)
    .join(" • ");
  const canAsk = phase === "QUESTIONING" && question.trim().length > 0;
  const canSubmitSolution = phase === "QUESTIONING" && solution.trim().length > 0;

  return (
    <GameRoomFrame
      connected={socket.connected}
      showReconnectAction={socket.showReconnectAction}
      onReconnect={socket.reconnect}
      gameId={gameId}
      phase={phase}
      showTransition={showTransition}
      tutorialId={`room-${gameId}`}
      tutorialSteps={["game.tutorial.turtle_soup.0", "game.tutorial.turtle_soup.1", "game.tutorial.turtle_soup.2"].map((key) => t(key))}
      title={room?.name || t("game.turtleTitle")}
      phaseText={phaseText}
      phaseEndsAt={state?.phaseEndsAt}
      aliveCount={alivePlayers.length}
      playerCount={players.length}
      headerExtra={<BookOpen className="h-4 w-4 text-violet-500" />}
      chatMessages={chatMessages}
      myPlayerId={state?.myPlayerId}
      onSendChat={(type, content) => {
        const sent = socket.sendChat(type, content);
        if (!sent) {
          toast.error(t("game.chat.sendFailed"));
        }
      }}
    >
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_320px]">
        <div className="space-y-4">
          <Card className="p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <div className="text-xs text-muted-foreground">{t("game.currentCase")}</div>
                <h3 className="text-xl font-semibold">{caseTitle}</h3>
              </div>
              <Badge variant={phase === "SETTLEMENT" ? "secondary" : "outline"}>{phase}</Badge>
            </div>
            <p className="mt-3 rounded-md border bg-slate-50 p-3 text-sm leading-6 text-slate-700">
              {surface || t("game.waitSurface")}
            </p>
            {v2 && phase !== "WAITING" && phase !== "SETTLEMENT" && (
              <div className="mt-3 space-y-2 text-sm">
                <div className="flex flex-wrap gap-2 text-muted-foreground">
                  <span>{labels.budget} {questionCount}/{maxQuestions}</span>
                  <span aria-hidden="true">·</span>
                  <span>{labels.hint} {Number(extra.hintCount || 0)}/{Number(extra.maxHints || 2)}</span>
                </div>
                {extra.hostThinking === true && (
                  <div role="status" className="flex items-center gap-2 text-violet-700 dark:text-violet-300">
                    <LoaderCircle className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />
                    <span>{labels.thinking}</span>
                  </div>
                )}
                {hostVerdict && <p role="status" className="rounded-md bg-muted p-3">{hostVerdict}</p>}
                {phase === "FINAL_ANSWER" && (
                  <div className="rounded-md border border-amber-300 bg-amber-50 p-3 text-amber-950 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-100">
                    <p className="font-medium">{labels.final}</p>
                    <p className="mt-1 leading-6">{labels.finalHelp}</p>
                  </div>
                )}
              </div>
            )}
          </Card>

          <Card className="p-4">
            <div className="mb-3 flex items-center gap-2 text-sm font-medium">
              <Lightbulb className="h-4 w-4 text-amber-500" />
              {v2 ? labels.confirmed : t("game.confirmedClues")}
            </div>
            {knownClues.length ? (
              <div className="flex flex-wrap gap-2">
                {knownClues.map((clue) => (
                  <Badge key={clue} variant="secondary" className="max-w-full whitespace-normal text-left">
                    {clue}
                  </Badge>
                ))}
              </div>
            ) : (
              <div className="text-sm text-muted-foreground">{t("game.noClues")}</div>
            )}
          </Card>

          <Card className="p-4">
            <div className="mb-3 flex items-center gap-2 text-sm font-medium">
              <HelpCircle className="h-4 w-4 text-blue-500" />
              {t("game.qaHistory")}
            </div>
            <div className="space-y-2">
              {qaHistory.map((item, index) => (
                <div key={item.id || `${index}-${item.question}`} className="rounded-md border bg-background p-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant={item.aiGenerated ? "secondary" : "outline"}>{item.aiGenerated ? t("game.aiPlayer") : t("game.player")}</Badge>
                    {v2 && <span className="text-xs text-muted-foreground">{item.displayName || players.find((player) => player.playerId === item.actorId)?.displayName}</span>}
                    <span className="font-medium">{item.question}</span>
                  </div>
                  <div className="mt-2 text-foreground">{t("game.hostReply", { answer: item.answer })}</div>
                  {item.duplicate && <p className="mt-1 text-xs text-muted-foreground">{labels.duplicate}</p>}
                </div>
              ))}
              {!qaHistory.length && <div className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">{t("game.noQa")}</div>}
            </div>
          </Card>

          {phase === "SETTLEMENT" && state && (
            <Card className="space-y-3 p-4">
              <div className="flex items-center gap-2 text-sm font-medium">
                <Trophy className="h-4 w-4 text-emerald-500" />
                {t("game.solutionTitle")}
              </div>
              <p className="rounded-md bg-emerald-50 p-3 text-sm leading-6 text-emerald-900">{revealedSolution || t("game.noSolution")}</p>
              {hostVerdict && <p className="text-sm text-muted-foreground">{hostVerdict}</p>}
              <SettlementPanel gameId={gameId} state={state} userKey={userKey} />
            </Card>
          )}

          <GameLogPanel gameId={gameId} roomId={room?.id} archiveId={state?.extra?.archiveId} viewVersion={state?.extra?.viewVersion} logs={state?.logs} emptyText={t("game.logEmpty.turtle")} />
        </div>

        <div className="space-y-4">
          <Card className="p-4">
            <PlayerGrid
          personas={personas}
              players={players}
              logs={state?.logs}
              myPlayerId={state?.myPlayerId}
              phase={phase}
              votingPhase="NONE"
              speakingPhase="NONE"
              onSelectPlayer={() => undefined}
            />
          </Card>

          <Card className="space-y-3 p-4">
            {phase === "WAITING" && (
              <>
                <p className="text-sm text-muted-foreground">{t("game.waitSurfaceHost")}</p>
                <Button data-testid="game-start-btn" onClick={startGame} disabled={startMutation.isPending} className="w-full">
                  <Play className="mr-2 h-4 w-4" /> {t("lobby.startGame")}
                </Button>
              </>
            )}
            {v2 && state && phase !== "WAITING" && phase !== "SETTLEMENT" && (
              <>
                <p className="text-xs leading-5 text-muted-foreground">{labels.waiting}</p>
                <V2ActionPanel
                  state={state}
                  pending={actionMutation.isPending}
                  onAction={(action) => actionMutation.mutate(action, { onError: (error: unknown) => handleActionError(error, "game.askFailed") })}
                />
              </>
            )}
            {!v2 && phase === "QUESTIONING" && (
              <>
                <div className="space-y-2">
                  <div className="text-sm font-medium">{t("game.askHost")}</div>
                  <Textarea data-testid="turtle-question-input" value={question} onChange={(event) => setQuestion(event.target.value)} placeholder={t("game.questionPlaceholder")} />
                  <Button data-testid="turtle-question-submit-btn" className="w-full" disabled={!canAsk || askMutation.isPending} onClick={() => askMutation.mutate()}>
                    <Send className="mr-2 h-4 w-4" /> {t("game.ask")}
                  </Button>
                </div>
                <div className="space-y-2">
                  <div className="text-sm font-medium">{t("game.submitSolutionTitle")}</div>
                  <Textarea data-testid="turtle-solution-input" value={solution} onChange={(event) => setSolution(event.target.value)} placeholder={t("game.solutionPlaceholder")} />
                  <Button data-testid="turtle-solution-submit-btn" variant="secondary" className="w-full" disabled={!canSubmitSolution || solutionMutation.isPending} onClick={() => solutionMutation.mutate()}>
                    {t("game.submitSolution")}
                  </Button>
                </div>
              </>
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
      </div>
    </GameRoomFrame>
  );
};

export default TurtleSoupRoom;
