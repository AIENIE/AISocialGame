import { useMemo, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import { BookOpen, HelpCircle, Lightbulb, Play, Send } from "lucide-react";
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

type QaItem = {
  playerId?: string;
  displayName?: string;
  ai?: boolean;
  question?: string;
  answerType?: string;
  answer?: string;
  clue?: string;
};

const answerLabel: Record<string, string> = {
  YES: "是",
  NO: "否",
  CLOSE: "接近",
  UNKNOWN: "不重要",
};

const TurtleSoupRoom = () => {
  const runtime = useRoomRuntime({ defaultGameId: "turtle_soup", recoverableMessages: ["当前不需要你提问"] });
  const {
    gameId,
    room,
    state,
    players,
    alivePlayers,
    currentSpeaker,
    phase,
    personas,
    selectedAiId,
    setSelectedAiId,
    canAddAi,
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
  const [finalGuess, setFinalGuess] = useState("");

  const extra = state?.extra || {};
  const qaHistory = useMemo(() => (Array.isArray(extra.qaHistory) ? (extra.qaHistory as QaItem[]) : []), [extra.qaHistory]);
  const confirmedClues = useMemo(() => (Array.isArray(extra.confirmedClues) ? (extra.confirmedClues as string[]) : []), [extra.confirmedClues]);
  const questionCount = Number(extra.questionCount || 0);
  const questionLimit = Number(extra.questionLimit || 0);
  const isMyTurn = phase === "QUESTIONING" && state?.mySeatNumber === state?.currentSeat;

  const questionMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "ASK_QUESTION", content: question.trim() }),
    onSuccess: () => {
      setQuestion("");
      invalidateRuntime();
    },
    onError: (error: unknown) => handleActionError(error, "提问失败"),
  });

  const guessMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "FINAL_GUESS", content: finalGuess.trim() }),
    onSuccess: invalidateRuntime,
    onError: (error: unknown) => handleActionError(error, "提交解答失败"),
  });

  const phaseText = `阶段：${phase}${currentSpeaker ? ` • 当前提问：${currentSpeaker.displayName}` : ""}${questionLimit ? ` • ${questionCount}/${questionLimit} 问` : ""}`;

  return (
    <GameRoomFrame
      connected={socket.connected}
      showReconnectAction={socket.showReconnectAction}
      onReconnect={socket.reconnect}
      gameId={gameId}
      phase={phase}
      showTransition={showTransition}
      tutorialId={`room-${gameId}`}
      tutorialSteps={["阅读汤面后轮流向 AI 主持提问。", "主持只会回答是、否、不重要或接近。", "认为真相完整时提交最终解答。"]}
      title={room?.name || "海龟汤房间"}
      phaseText={phaseText}
      phaseEndsAt={state?.phaseEndsAt}
      aliveCount={alivePlayers.length}
      playerCount={players.length}
      headerExtra={<BookOpen className="h-4 w-4 text-emerald-600" />}
      chatMessages={chatMessages}
      myPlayerId={state?.myPlayerId}
      onSendChat={(type, content) => {
        const sent = socket.sendChat(type, content);
        if (!sent) {
          toast.error("聊天发送失败，连接未就绪");
        }
      }}
    >
      <Card className="p-4">
        <PlayerGrid
          players={players}
          myPlayerId={state?.myPlayerId}
          selectedPlayerId={null}
          currentSeat={state?.currentSeat}
          phase={phase}
          votingPhase="__NONE__"
          speakingPhase="QUESTIONING"
          onSelectPlayer={() => {}}
        />

        <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-3">
          <Card className="space-y-3 p-3 lg:col-span-2">
            <div className="flex items-center justify-between">
              <div>
                <div className="text-xs text-muted-foreground">汤面</div>
                <h3 className="text-lg font-bold">{String(extra.soupTitle || "等待开局")}</h3>
              </div>
              <Badge variant="outline">{String(extra.difficulty || "MVP")}</Badge>
            </div>
            <p className="leading-7 text-slate-700">{String(extra.soupPrompt || "房主开局后会展示汤面。")}</p>
            {phase === "SETTLEMENT" && (
              <div className="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">
                <div className="mb-1 font-semibold">汤底</div>
                {String(extra.solution || "暂无")}
              </div>
            )}
          </Card>

          <Card className="space-y-3 p-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">操作区</span>
              <Badge variant="outline">{phase}</Badge>
            </div>
            {phase === "WAITING" && (
              <div className="space-y-2">
                <p className="text-sm text-muted-foreground">房主可添加 AI 玩家后开始。</p>
                <Button data-testid="game-start-btn" onClick={startGame} disabled={startMutation.isPending} className="w-full">
                  <Play className="mr-2 h-4 w-4" /> 开始游戏
                </Button>
              </div>
            )}
            {phase === "QUESTIONING" && (
              <div className="space-y-2">
                <Textarea value={question} onChange={(event) => setQuestion(event.target.value)} placeholder="提出一个是/否问题" rows={3} />
                <Button data-testid="game-question-submit-btn" className="w-full" disabled={!isMyTurn || !question.trim() || questionMutation.isPending} onClick={() => questionMutation.mutate()}>
                  <HelpCircle className="mr-2 h-4 w-4" /> 提交提问
                </Button>
                {!isMyTurn && <p className="text-xs text-muted-foreground">等待 {currentSpeaker?.displayName || "其他玩家"} 提问。</p>}
              </div>
            )}
            {(phase === "QUESTIONING" || phase === "SOLVING") && (
              <div className="space-y-2 border-t pt-3">
                <Textarea value={finalGuess} onChange={(event) => setFinalGuess(event.target.value)} placeholder="提交最终解答" rows={4} />
                <Button data-testid="game-final-guess-btn" variant="secondary" className="w-full" disabled={!finalGuess.trim() || guessMutation.isPending} onClick={() => guessMutation.mutate()}>
                  <Send className="mr-2 h-4 w-4" /> 提交解答
                </Button>
              </div>
            )}
            {phase === "SETTLEMENT" && state && <SettlementPanel gameId={gameId} state={state} userKey={userKey} />}
          </Card>
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="space-y-3 p-4 lg:col-span-2">
          <div className="flex items-center justify-between">
            <h3 className="font-semibold">问答记录</h3>
            <Badge variant="secondary">{qaHistory.length} 条</Badge>
          </div>
          <div className="space-y-3">
            {qaHistory.map((item, index) => (
              <div key={`${item.playerId}-${index}`} className="rounded-md border bg-white p-3 text-sm">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <span className="font-medium">{item.displayName || "玩家"}</span>
                  <Badge variant="outline">{answerLabel[item.answerType || "UNKNOWN"] || item.answerType}</Badge>
                </div>
                <div className="text-slate-700">问：{item.question}</div>
                <div className="mt-1 text-emerald-700">答：{item.answer}</div>
              </div>
            ))}
            {qaHistory.length === 0 && <p className="text-sm text-muted-foreground">暂无提问。</p>}
          </div>
        </Card>

        <div className="space-y-4">
          <Card className="space-y-3 p-4">
            <div className="flex items-center gap-2 font-semibold">
              <Lightbulb className="h-4 w-4 text-amber-500" />
              已确认线索
            </div>
            <div className="space-y-2 text-sm text-slate-700">
              {confirmedClues.map((clue) => (
                <div key={clue} className="rounded-md bg-amber-50 px-3 py-2">
                  {clue}
                </div>
              ))}
              {confirmedClues.length === 0 && <p className="text-muted-foreground">主持回答后会整理线索。</p>}
            </div>
          </Card>
          <AiSeatControl
            personas={personas}
            selectedAiId={selectedAiId}
            onSelectedAiIdChange={setSelectedAiId}
            seatCount={room?.seats?.length ?? 0}
            maxPlayers={room?.maxPlayers}
            canAddAi={canAddAi}
            onAddAi={() => addAiMutation.mutate(selectedAiId)}
          />
        </div>
      </div>

      <GameLogPanel logs={state?.logs} emptyText="暂无日志，等待开局或提问。" />
    </GameRoomFrame>
  );
};

export default TurtleSoupRoom;
