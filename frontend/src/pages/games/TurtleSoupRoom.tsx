import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import { BookOpen, HelpCircle, Lightbulb, Play, Send, Trophy } from "lucide-react";
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
  question: string;
  answer: string;
  clues?: string[];
  aiGenerated?: boolean;
  time?: string;
};

const stringList = (value: unknown): string[] => {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
};

const qaList = (value: unknown): QaItem[] => {
  return Array.isArray(value)
    ? value
        .filter((item): item is Record<string, unknown> => item !== null && typeof item === "object")
        .map((item) => ({
          question: typeof item.question === "string" ? item.question : "",
          answer: typeof item.answer === "string" ? item.answer : "",
          clues: stringList(item.clues),
          aiGenerated: Boolean(item.aiGenerated),
          time: typeof item.time === "string" ? item.time : undefined,
        }))
    : [];
};

const TurtleSoupRoom = () => {
  const runtime = useRoomRuntime({ defaultGameId: "turtle_soup" });
  const {
    gameId,
    room,
    state,
    players,
    alivePlayers,
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
  const [solution, setSolution] = useState("");

  const extra = state?.extra || {};
  const knownClues = stringList(extra.knownClues);
  const qaHistory = qaList(extra.qaHistory);
  const questionCount = Number(extra.questionCount || 0);
  const maxQuestions = Number(extra.maxQuestions || 0);
  const surface = typeof extra.surface === "string" ? extra.surface : "";
  const caseTitle = typeof extra.caseTitle === "string" ? extra.caseTitle : "海龟汤";
  const hostVerdict = typeof extra.hostVerdict === "string" ? extra.hostVerdict : "";
  const revealedSolution = typeof extra.solution === "string" ? extra.solution : "";

  const askMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "ASK_QUESTION", content: question.trim() }),
    onSuccess: () => {
      setQuestion("");
      invalidateRuntime();
    },
    onError: (error: unknown) => handleActionError(error, "提问失败"),
  });

  const solutionMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "SUBMIT_SOLUTION", content: solution.trim() }),
    onSuccess: () => {
      invalidateRuntime();
    },
    onError: (error: unknown) => handleActionError(error, "提交解答失败"),
  });

  const phaseText = `阶段：${phase}${state?.round ? ` • 第${state.round}局` : ""}${maxQuestions ? ` • ${questionCount}/${maxQuestions} 问` : ""}`;
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
      tutorialSteps={["阅读汤面后用是/否问题缩小范围。", "AI 玩家会在开启辅助时自动补充追问。", "确认汤底后提交最终解答，结算页会揭示完整真相。"]}
      title={room?.name || "海龟汤房间"}
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
          toast.error("聊天发送失败，连接未就绪");
        }
      }}
    >
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_320px]">
        <div className="space-y-4">
          <Card className="p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <div className="text-xs text-muted-foreground">本局题目</div>
                <h3 className="text-xl font-semibold">{caseTitle}</h3>
              </div>
              <Badge variant={phase === "SETTLEMENT" ? "secondary" : "outline"}>{phase}</Badge>
            </div>
            <p className="mt-3 rounded-md border bg-slate-50 p-3 text-sm leading-6 text-slate-700">
              {surface || "等待房主开局后展示汤面。"}
            </p>
          </Card>

          <Card className="p-4">
            <div className="mb-3 flex items-center gap-2 text-sm font-medium">
              <Lightbulb className="h-4 w-4 text-amber-500" />
              已确认线索
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
              <div className="text-sm text-muted-foreground">还没有确认线索，先向 AI 主持提一个是/否问题。</div>
            )}
          </Card>

          <Card className="p-4">
            <div className="mb-3 flex items-center gap-2 text-sm font-medium">
              <HelpCircle className="h-4 w-4 text-blue-500" />
              问答历史
            </div>
            <div className="space-y-2">
              {qaHistory.map((item, index) => (
                <div key={`${index}-${item.question}`} className="rounded-md border bg-white p-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant={item.aiGenerated ? "secondary" : "outline"}>{item.aiGenerated ? "AI 玩家" : "玩家"}</Badge>
                    <span className="font-medium">{item.question}</span>
                  </div>
                  <div className="mt-2 text-slate-700">主持：{item.answer}</div>
                </div>
              ))}
              {!qaHistory.length && <div className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">暂无问答。</div>}
            </div>
          </Card>

          {phase === "SETTLEMENT" && state && (
            <Card className="space-y-3 p-4">
              <div className="flex items-center gap-2 text-sm font-medium">
                <Trophy className="h-4 w-4 text-emerald-500" />
                汤底揭示
              </div>
              <p className="rounded-md bg-emerald-50 p-3 text-sm leading-6 text-emerald-900">{revealedSolution || "暂无汤底"}</p>
              {hostVerdict && <p className="text-sm text-muted-foreground">{hostVerdict}</p>}
              <SettlementPanel gameId={gameId} state={state} userKey={userKey} />
            </Card>
          )}

          <GameLogPanel logs={state?.logs} emptyText="暂无日志，等待开局或提问。" />
        </div>

        <div className="space-y-4">
          <Card className="p-4">
            <PlayerGrid
              players={players}
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
                <p className="text-sm text-muted-foreground">房主开局后 AI 主持会展示汤面。</p>
                <Button data-testid="game-start-btn" onClick={startGame} disabled={startMutation.isPending} className="w-full">
                  <Play className="mr-2 h-4 w-4" /> 开始游戏
                </Button>
              </>
            )}
            {phase === "QUESTIONING" && (
              <>
                <div className="space-y-2">
                  <div className="text-sm font-medium">向主持提问</div>
                  <Textarea data-testid="turtle-question-input" value={question} onChange={(event) => setQuestion(event.target.value)} placeholder="例如：她上车前是否已经遇难？" />
                  <Button data-testid="turtle-question-submit-btn" className="w-full" disabled={!canAsk || askMutation.isPending} onClick={() => askMutation.mutate()}>
                    <Send className="mr-2 h-4 w-4" /> 提问
                  </Button>
                </div>
                <div className="space-y-2">
                  <div className="text-sm font-medium">提交最终解答</div>
                  <Textarea data-testid="turtle-solution-input" value={solution} onChange={(event) => setSolution(event.target.value)} placeholder="整理你的汤底推理" />
                  <Button data-testid="turtle-solution-submit-btn" variant="secondary" className="w-full" disabled={!canSubmitSolution || solutionMutation.isPending} onClick={() => solutionMutation.mutate()}>
                    提交解答
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
            onAddAi={() => addAiMutation.mutate(selectedAiId)}
          />
        </div>
      </div>
    </GameRoomFrame>
  );
};

export default TurtleSoupRoom;
