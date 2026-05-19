import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { personaApi, roomApi } from "@/services/api";
import { useAuth } from "@/hooks/useAuth";
import { useGameSocket } from "@/hooks/useGameSocket";
import { useGameEngine } from "@/hooks/useGameEngine";
import { ChatMessage } from "@/types";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { ChatPanel } from "@/components/game/ChatPanel";
import { ConnectionStatusBar } from "@/components/game/ConnectionStatusBar";
import { CountdownTimer } from "@/components/game/CountdownTimer";
import { PhaseTransition } from "@/components/game/PhaseTransition";
import { Bot, CheckSquare, Moon, Play, Sun, WifiOff } from "lucide-react";
import { toast } from "sonner";
import { SettlementPanel } from "@/components/game/SettlementPanel";
import { TutorialOverlay } from "@/components/tutorial/TutorialOverlay";
import { achievementApi, replayApi } from "@/services/v2Social";

const WerewolfRoom = () => {
  const { roomId, gameId } = useParams();
  const { user, displayName, token, loading, redirectToSsoLogin } = useAuth();
  const queryClient = useQueryClient();
  const playerId = user?.id || null;
  const [speakContent, setSpeakContent] = useState("");
  const [selectedVote, setSelectedVote] = useState<string | null>(null);
  const [nightTarget, setNightTarget] = useState<string | null>(null);
  const [selectedAiId, setSelectedAiId] = useState<string>("");
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [showTransition, setShowTransition] = useState(false);
  const prevPhaseRef = useRef<string | undefined>();
  const settlementSnapshotRef = useRef<string | null>(null);
  const userKey = user?.id || `guest:${displayName}`;

  const { data: personas = [] } = useQuery({ queryKey: ["personas"], queryFn: personaApi.list });
  const { data: room } = useQuery({ queryKey: ["room", roomId], queryFn: () => roomApi.detail(gameId || "", roomId || ""), enabled: !!roomId && !!gameId });

  const { stateQuery, startMutation, actionMutation } = useGameEngine(gameId || "werewolf", user ? roomId : undefined);

  const socket = useGameSocket({
    roomId,
    playerId,
    token,
    onStateChange: () => queryClient.invalidateQueries({ queryKey: ["game-state", roomId] }),
    onSeatChange: () => {
      queryClient.invalidateQueries({ queryKey: ["room", roomId] });
      queryClient.invalidateQueries({ queryKey: ["game-state", roomId] });
    },
    onPrivate: () => queryClient.invalidateQueries({ queryKey: ["game-state", roomId] }),
    onChat: (msg) => setChatMessages((prev) => [...prev.slice(-99), msg]),
  });

  useEffect(() => {
    if (personas.length > 0 && !selectedAiId) {
      setSelectedAiId(personas[0].id);
    }
  }, [personas, selectedAiId]);

  useEffect(() => {
    if (playerId && roomId) {
      stateQuery.refetch();
    }
  }, [playerId]);

  useEffect(() => {
    if (stateQuery.data?.phase !== "DAY_VOTE") {
      setSelectedVote(null);
    }
  }, [stateQuery.data?.phase]);

  const resolveApiMessage = (error: any, fallback: string) => error?.response?.data?.message || fallback;
  const handleActionError = (error: any, fallback: string) => {
    const message = resolveApiMessage(error, fallback);
    const recoverable = ["已完成投票", "当前不需要你发言", "房间已满", "当前阶段不支持该操作", "你已出局，无法行动"].some((item) =>
      message.includes(item)
    );
    if (recoverable) {
      toast.info(message);
    } else {
      toast.error(message);
    }
    queryClient.invalidateQueries({ queryKey: ["game-state", roomId] });
    queryClient.invalidateQueries({ queryKey: ["room", roomId] });
  };

  useEffect(() => {
    if (!stateQuery.data?.phase) {
      return;
    }
    if (stateQuery.data.phase !== prevPhaseRef.current && stateQuery.data.phase !== "WAITING") {
      setShowTransition(true);
      const timer = window.setTimeout(() => setShowTransition(false), 1800);
      prevPhaseRef.current = stateQuery.data.phase;
      return () => clearTimeout(timer);
    }
  }, [stateQuery.data?.phase]);

  const joinMutation = useMutation({
    mutationFn: () => {
      const password = room?.isPrivate ? window.prompt("请输入私密房间密码") || undefined : undefined;
      return roomApi.join(gameId || "", roomId || "", displayName, password);
    },
    onSuccess: (data) => {
      const pid = (data as any).selfPlayerId;
      if (pid && roomId) {
        queryClient.invalidateQueries({ queryKey: ["game-state", roomId] });
      }
    },
    onError: () => toast.error("加入房间失败"),
  });

  useEffect(() => {
    if (!room || loading || joinMutation.isSuccess || joinMutation.isPending) {
      return;
    }
    if (!user) {
      void redirectToSsoLogin();
      return;
    }
    if (room.seats?.some((seat) => seat.playerId === user.id)) {
      return;
    }
    joinMutation.mutate();
  }, [room, loading, token, playerId, user, redirectToSsoLogin]);

  const speakMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "SPEAK", content: speakContent || "结束发言" }),
    onSuccess: () => {
      setSpeakContent("");
      queryClient.invalidateQueries({ queryKey: ["game-state", roomId] });
    },
    onError: (error: any) => handleActionError(error, "发言失败"),
  });

  const voteMutation = useMutation({
    mutationFn: () => actionMutation.mutateAsync({ type: "VOTE", targetPlayerId: selectedVote || "", abstain: false }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["game-state", roomId] }),
    onError: (error: any) => handleActionError(error, "投票失败"),
  });

  const nightMutation = useMutation({
    mutationFn: (payload: { action: string; targetPlayerId?: string; useHeal?: boolean }) =>
      actionMutation.mutateAsync({ type: "NIGHT_ACTION", nightAction: payload.action, targetPlayerId: payload.targetPlayerId, useHeal: payload.useHeal }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["game-state", roomId] }),
    onError: (error: any) => handleActionError(error, "夜晚行动失败"),
  });

  const addAiMutation = useMutation({
    mutationFn: (personaId: string) => roomApi.addAi(gameId || "", roomId || "", personaId),
    onSuccess: () => {
      toast.success("AI 已加入");
      queryClient.invalidateQueries({ queryKey: ["room", roomId] });
      queryClient.invalidateQueries({ queryKey: ["game-state", roomId] });
    },
    onError: (error: any) => handleActionError(error, "添加 AI 失败"),
  });

  const state = stateQuery.data;
  const players = state?.players || [];
  const alivePlayers = useMemo(() => players.filter((p) => p.alive), [players]);
  const currentSpeaker = players.find((p) => p.seatNumber === state?.currentSeat);
  const phase = state?.phase || "WAITING";
  const myRole = state?.myRole;
  const pending = state?.pendingAction;
  const canSpeak = phase === "DAY_DISCUSS" && state?.mySeatNumber === state?.currentSeat;
  const hasVoted = !!(state?.myPlayerId && state?.votes?.[state.myPlayerId]);
  const canAddAi = !!selectedAiId && (room?.seats?.length ?? 0) < (room?.maxPlayers ?? Number.MAX_SAFE_INTEGER);

  useEffect(() => {
    if (!roomId || !state || phase !== "SETTLEMENT") {
      return;
    }
    const settlementId = `${roomId}-${state.round}-${state.logs?.length || 0}`;
    if (settlementSnapshotRef.current === settlementId) {
      return;
    }
    settlementSnapshotRef.current = settlementId;

    const me = state.players.find((p) => p.playerId === state.myPlayerId);
    const didWin = !!state.winner && !!me?.alive;
    const unlocked = achievementApi.applySettlement(userKey, didWin);
    unlocked.forEach((item) => {
      const def = achievementApi.listDefinitions().find((d) => d.code === item.code);
      toast.success(`成就解锁：${def?.name || item.code}`);
    });

    const archive = replayApi.fromState(gameId || "werewolf", room, state);
    replayApi.save(userKey, archive);
  }, [phase, state?.roomId, state?.round, state?.logs?.length, userKey, roomId, gameId, room?.name]);

  return (
    <>
      <ConnectionStatusBar connected={socket.connected} showReconnectAction={socket.showReconnectAction} onReconnect={socket.reconnect} />
      <PhaseTransition gameId={gameId} phase={phase} visible={showTransition} />
      <TutorialOverlay
        id={`room-${gameId || "werewolf"}`}
        steps={["你当前处于狼人杀房间，顶部显示阶段与倒计时。", "白天讨论后进入投票，点击头像可快速选择并确认目标。", "结算后可直接添加好友并在回放中心复盘。"]}
      />

      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-xl font-bold">{room?.name || "狼人杀房间"}</h2>
            <p className="text-sm text-muted-foreground" data-testid="game-phase-text">
              阶段：{phase} {currentSpeaker ? `• 当前发言：${currentSpeaker.displayName}` : ""} {state?.round ? `• 第${state.round}天` : ""}
            </p>
          </div>
          <div className="flex items-center gap-2">
            {phase === "NIGHT" ? <Moon className="h-4 w-4 text-blue-500" /> : <Sun className="h-4 w-4 text-amber-500" />}
            <CountdownTimer phaseEndsAt={state?.phaseEndsAt} />
            <Badge variant="secondary">
              {alivePlayers.length}/{players.length} 存活
            </Badge>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-4">
          <div className="space-y-4 xl:col-span-3">
            <Card className="p-4">
              <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                {players.map((p) => (
                  <div
                    key={p.playerId}
                    data-testid="game-player-card"
                    data-player-id={p.playerId}
                    data-is-me={p.playerId === state?.myPlayerId ? "true" : "false"}
                    data-alive={p.alive ? "true" : "false"}
                    className={`flex items-center gap-3 rounded-lg border p-2 transition ${p.alive ? "border-slate-200" : "border-red-200 bg-red-50"} ${
                      selectedVote === p.playerId ? "ring-2 ring-amber-400" : ""
                    } ${phase === "DAY_VOTE" && p.playerId !== state?.myPlayerId ? "cursor-pointer hover:bg-amber-50" : ""}`}
                    onClick={() => {
                      if (phase !== "DAY_VOTE" || p.playerId === state?.myPlayerId || !p.alive) return;
                      setSelectedVote(p.playerId);
                    }}
                  >
                    <Avatar className="h-10 w-10">
                      <AvatarImage src={p.avatar} />
                      <AvatarFallback>{p.displayName[0]}</AvatarFallback>
                    </Avatar>
                    <div className="min-w-0 flex-1">
                      <div className="truncate font-medium">{p.displayName}</div>
                      <div className="text-xs text-muted-foreground">座位 {p.seatNumber + 1}</div>
                    </div>
                    {!p.alive && <Badge variant="destructive">出局</Badge>}
                    {p.connectionStatus === "DISCONNECTED" && <WifiOff className="h-4 w-4 text-slate-400" />}
                    {p.connectionStatus === "AI_TAKEOVER" && <Badge variant="outline" className="text-amber-600">托管</Badge>}
                    {state?.currentSeat === p.seatNumber && phase === "DAY_DISCUSS" && <Badge variant="outline">发言中</Badge>}
                  </div>
                ))}
              </div>

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
                      <Button data-testid="game-start-btn" onClick={() => startMutation.mutate(undefined, { onError: (err: any) => toast.error(err?.response?.data?.message || "开局失败") })} disabled={startMutation.isPending} className="w-full">
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
                              {alivePlayers
                                .filter((p) => p.playerId !== state?.myPlayerId)
                                .map((p) => (
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
                              {alivePlayers
                                .filter((p) => p.playerId !== state?.myPlayerId)
                                .map((p) => (
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

                <Card className="space-y-3 p-3">
                  <div className="flex items-center justify-between text-sm">
                    <span>添加 AI 补位</span>
                    <Badge data-testid="game-ai-seat-count">
                      {room?.seats?.length ?? 0}/{room?.maxPlayers}
                    </Badge>
                  </div>
                  <Select value={selectedAiId} onValueChange={setSelectedAiId}>
                    <SelectTrigger>
                      <SelectValue placeholder="选择 AI 人设" />
                    </SelectTrigger>
                    <SelectContent>
                      {personas.map((ai) => (
                        <SelectItem key={ai.id} value={ai.id}>
                          {ai.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button data-testid="game-add-ai-btn" variant="secondary" onClick={() => addAiMutation.mutate(selectedAiId)} disabled={!canAddAi}>
                    <Bot className="mr-2 h-4 w-4" /> 添加 AI
                  </Button>
                </Card>
              </div>
            </Card>

            <Card className="p-4" data-testid="game-logs-panel">
              <div className="mb-3 flex items-center justify-between">
                <h3 className="font-semibold">游戏日志</h3>
                <Badge variant="outline">实时</Badge>
              </div>
              <ScrollArea className="h-64 pr-2">
                <div className="space-y-2 text-sm">
                  {(state?.logs || []).map((log, idx) => (
                    <div key={idx} data-testid="game-log-item" className="flex items-center gap-2">
                      <span className="text-xs text-muted-foreground">{new Date(log.time).toLocaleTimeString()}</span>
                      <span>{log.message}</span>
                    </div>
                  ))}
                  {(!state?.logs || state.logs.length === 0) && (
                    <div data-testid="game-log-empty" className="text-sm text-muted-foreground">
                      暂无日志，等待对局操作。
                    </div>
                  )}
                </div>
              </ScrollArea>
            </Card>
          </div>

          <ChatPanel
            messages={chatMessages}
            myPlayerId={state?.myPlayerId}
            onSend={(type, content) => {
              const sent = socket.sendChat(type, content);
              if (!sent) {
                toast.error("聊天发送失败，连接未就绪");
              }
            }}
          />
        </div>
      </div>
    </>
  );
};

export default WerewolfRoom;
