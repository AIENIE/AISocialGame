import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
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

const UndercoverRoom = () => {
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
  const [speakContent, setSpeakContent] = useState("");
  const [selectedVote, setSelectedVote] = useState<string | null>(null);

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
    onError: (error: unknown) => handleActionError(error, "发言提交失败"),
  });

  const voteMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "VOTE", targetPlayerId: selectedVote || "", abstain: false }),
    onSuccess: invalidateRuntime,
    onError: (error: unknown) => handleActionError(error, "投票失败"),
  });

  const canSpeak = phase === "DESCRIPTION" && state?.mySeatNumber === state?.currentSeat;
  const hasVoted = !!(state?.myPlayerId && state?.votes?.[state.myPlayerId]);
  const canVote = phase === "VOTING" && !!selectedVote && !hasVoted;
  const phaseText = `阶段：${phase}${currentSpeaker ? ` • 当前发言：${currentSpeaker.displayName}` : ""}${state?.round ? ` • 第${state.round}轮` : ""}`;

  return (
    <GameRoomFrame
      connected={socket.connected}
      showReconnectAction={socket.showReconnectAction}
      onReconnect={socket.reconnect}
      gameId={gameId}
      phase={phase}
      showTransition={showTransition}
      tutorialId={`room-${gameId}`}
      tutorialSteps={["这是房间主视图，左侧展示玩家与阶段。", "轮到你时可提交发言，投票阶段点击头像完成投票。", "结算后可添加好友并在回放中心复盘。"]}
      title={room?.name || "卧底房间"}
      phaseText={phaseText}
      phaseEndsAt={state?.phaseEndsAt}
      aliveCount={alivePlayers.length}
      playerCount={players.length}
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
          selectedPlayerId={selectedVote}
          currentSeat={state?.currentSeat}
          phase={phase}
          votingPhase="VOTING"
          speakingPhase="DESCRIPTION"
          onSelectPlayer={setSelectedVote}
        />

        <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-3">
          <Card className="border-dashed p-3">
            <div className="mb-2 text-xs text-muted-foreground">我的词语</div>
            <div className="text-lg font-bold">{state?.myWord || "等待发牌"}</div>
            <div className="mt-1 text-xs text-muted-foreground">{state?.myRole === "UNDERCOVER" ? "你的身份：卧底" : "谨慎描述，避免暴露身份"}</div>
          </Card>

          <Card className="p-3">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-medium">操作区</span>
              <Badge variant="outline">{phase}</Badge>
            </div>
            {phase === "WAITING" && (
              <div className="space-y-2">
                <p className="text-sm text-muted-foreground">满足人数后房主可以开局。</p>
                <Button data-testid="game-start-btn" onClick={startGame} disabled={startMutation.isPending} className="w-full">
                  <Play className="mr-2 h-4 w-4" /> 开始游戏
                </Button>
              </div>
            )}
            {canSpeak && (
              <div className="space-y-2">
                <Input data-testid="game-speak-input" value={speakContent} onChange={(e) => setSpeakContent(e.target.value)} placeholder="输入你的描述" />
                <Button data-testid="game-speak-submit-btn" className="w-full" onClick={() => speakMutation.mutate()} disabled={speakMutation.isPending}>
                  <Send className="mr-2 h-4 w-4" /> 提交发言
                </Button>
              </div>
            )}
            {phase === "VOTING" && (
              <div className="space-y-2">
                <div className="text-xs text-muted-foreground">点击玩家头像选择目标后，点击按钮提交投票。</div>
                <Button data-testid="game-vote-submit-btn" className="w-full" disabled={!canVote || voteMutation.isPending} onClick={() => voteMutation.mutate()}>
                  <CheckSquare className="mr-2 h-4 w-4" /> 投票
                </Button>
              </div>
            )}
            {phase === "SETTLEMENT" && state && <SettlementPanel gameId={gameId} state={state} userKey={userKey} />}
            {!canSpeak && phase === "DESCRIPTION" && <div className="text-sm text-muted-foreground">等待 {currentSpeaker?.displayName || "玩家"} 发言...</div>}
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
      </Card>

      <GameLogPanel logs={state?.logs} emptyText="暂无日志，等待开局或操作。" />
    </GameRoomFrame>
  );
};

export default UndercoverRoom;
