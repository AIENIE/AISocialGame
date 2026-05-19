import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
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
import { useRoomRuntime } from "./shared/useRoomRuntime";

interface NightActionPayload {
  action: string;
  targetPlayerId?: string;
  useHeal?: boolean;
}

const WerewolfRoom = () => {
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
  const [nightTarget, setNightTarget] = useState<string | null>(null);

  useEffect(() => {
    if (phase !== "DAY_VOTE") {
      setSelectedVote(null);
    }
  }, [phase]);

  const speakMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "SPEAK", content: speakContent || "结束发言" }),
    onSuccess: () => {
      setSpeakContent("");
      invalidateRuntime();
    },
    onError: (error: unknown) => handleActionError(error, "发言失败"),
  });

  const voteMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "VOTE", targetPlayerId: selectedVote || "", abstain: false }),
    onSuccess: invalidateRuntime,
    onError: (error: unknown) => handleActionError(error, "投票失败"),
  });

  const nightMutation = useMutation({
    mutationFn: (payload: NightActionPayload) =>
      actionMutation.mutateAsync({ type: "NIGHT_ACTION", nightAction: payload.action, targetPlayerId: payload.targetPlayerId, useHeal: payload.useHeal }),
    onSuccess: invalidateRuntime,
    onError: (error: unknown) => handleActionError(error, "夜晚行动失败"),
  });

  const myRole = state?.myRole;
  const pending = state?.pendingAction;
  const canSpeak = phase === "DAY_DISCUSS" && state?.mySeatNumber === state?.currentSeat;
  const hasVoted = !!(state?.myPlayerId && state?.votes?.[state.myPlayerId]);
  const phaseText = `阶段：${phase}${currentSpeaker ? ` • 当前发言：${currentSpeaker.displayName}` : ""}${state?.round ? ` • 第${state.round}天` : ""}`;
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
      tutorialSteps={["你当前处于狼人杀房间，顶部显示阶段与倒计时。", "白天讨论后进入投票，点击头像可快速选择并确认目标。", "结算后可直接添加好友并在回放中心复盘。"]}
      title={room?.name || "狼人杀房间"}
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
          votingPhase="DAY_VOTE"
          speakingPhase="DAY_DISCUSS"
          onSelectPlayer={setSelectedVote}
        />

        <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-3">
          <Card className="p-3">
            <div className="mb-1 text-xs text-muted-foreground">我的身份</div>
            <div className="text-lg font-bold">{myRole || "未发牌"}</div>
            <div className="mt-1 text-xs text-muted-foreground">仅对你可见。夜晚待办事项会在下方提示。</div>
          </Card>

          <Card className="p-3">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-medium">操作区</span>
              <Badge variant="outline">{phase}</Badge>
            </div>
            {phase === "WAITING" && (
              <div className="space-y-2">
                <p className="text-sm text-muted-foreground">满足人数后由房主开局。</p>
                <Button data-testid="game-start-btn" onClick={startGame} disabled={startMutation.isPending} className="w-full">
                  <Play className="mr-2 h-4 w-4" /> 开始游戏
                </Button>
              </div>
            )}
            {phase === "NIGHT" && pending && (
              <div className="space-y-2">
                <div className="text-sm text-muted-foreground">{pending.description}</div>
                {pending.type === "WITCH" ? (
                  <div className="space-y-2">
                    <div className="flex gap-2">
                      <Button variant="secondary" className="flex-1" onClick={() => nightMutation.mutate({ action: "WITCH_SAVE", useHeal: true })}>
                        解药
                      </Button>
                      <Button variant="outline" className="flex-1" onClick={() => nightMutation.mutate({ action: "WITCH_SAVE", useHeal: false })}>
                        放弃解药
                      </Button>
                    </div>
                    <Select value={nightTarget || undefined} onValueChange={setNightTarget}>
                      <SelectTrigger>
                        <SelectValue placeholder="毒杀目标" />
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
                      毒药
                    </Button>
                  </div>
                ) : (
                  <>
                    <Select value={nightTarget || undefined} onValueChange={setNightTarget}>
                      <SelectTrigger>
                        <SelectValue placeholder="选择目标" />
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
                      提交夜晚行动
                    </Button>
                  </>
                )}
              </div>
            )}
            {canSpeak && (
              <div className="space-y-2">
                <Input data-testid="game-speak-input" value={speakContent} onChange={(e) => setSpeakContent(e.target.value)} placeholder="输入你的发言" />
                <Button data-testid="game-speak-submit-btn" className="w-full" onClick={() => speakMutation.mutate()} disabled={speakMutation.isPending}>
                  结束发言
                </Button>
              </div>
            )}
            {phase === "DAY_VOTE" && (
              <div className="space-y-2">
                <div className="text-xs text-muted-foreground">点击玩家头像选择目标后，点击按钮提交投票。</div>
                <Button data-testid="game-vote-submit-btn" className="w-full" disabled={!selectedVote || hasVoted || voteMutation.isPending} onClick={() => voteMutation.mutate()}>
                  <CheckSquare className="mr-2 h-4 w-4" /> 投票
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
            onAddAi={() => addAiMutation.mutate(selectedAiId)}
          />
        </div>
      </Card>

      <GameLogPanel logs={state?.logs} emptyText="暂无日志，等待对局操作。" />
    </GameRoomFrame>
  );
};

export default WerewolfRoom;
