import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { toast } from "sonner";
import { useAuth } from "@/hooks/useAuth";
import { useGameEngine } from "@/hooks/useGameEngine";
import { useGameSocket } from "@/hooks/useGameSocket";
import { getApiErrorMessage, personaApi, roomApi } from "@/services/api";
import { achievementApi, replayApi } from "@/services/v2Social";
import { ChatMessage } from "@/types";

const DEFAULT_RECOVERABLE_MESSAGES = ["已完成投票", "当前不需要你发言", "房间已满", "当前阶段不支持该操作"];

interface UseRoomRuntimeOptions {
  defaultGameId: string;
  recoverableMessages?: string[];
}

export function useRoomRuntime({ defaultGameId, recoverableMessages = [] }: UseRoomRuntimeOptions) {
  const { roomId, gameId } = useParams();
  const effectiveGameId = gameId || defaultGameId;
  const queryClient = useQueryClient();
  const { user, displayName, token, loading, redirectToSsoLogin } = useAuth();
  const playerId = user?.id || null;
  const [selectedAiId, setSelectedAiId] = useState<string>("");
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [showTransition, setShowTransition] = useState(false);
  const prevPhaseRef = useRef<string | undefined>();
  const settlementSnapshotRef = useRef<string | null>(null);
  const joinAttemptedRoomRef = useRef<string | null>(null);
  const userKey = user?.id || `guest:${displayName}`;

  const { data: personas = [] } = useQuery({
    queryKey: ["personas"],
    queryFn: personaApi.list,
  });

  const { data: room } = useQuery({
    queryKey: ["room", roomId],
    queryFn: () => roomApi.detail(effectiveGameId, roomId || ""),
    enabled: !!roomId && !!effectiveGameId,
  });

  const { stateQuery, startMutation, actionMutation } = useGameEngine(effectiveGameId, user ? roomId : undefined);

  const invalidateRuntime = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ["game-state", roomId] });
  }, [queryClient, roomId]);

  const invalidateRoom = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ["room", roomId] });
    queryClient.invalidateQueries({ queryKey: ["game-state", roomId] });
  }, [queryClient, roomId]);

  const socket = useGameSocket({
    roomId,
    playerId,
    token,
    onStateChange: invalidateRuntime,
    onSeatChange: invalidateRoom,
    onPrivate: (event) => {
      if (event.type === "SAFETY_NOTICE") {
        const message = typeof event.payload?.message === "string" ? event.payload.message : "内容未通过安全检查";
        toast.warning(message);
      }
      invalidateRuntime();
    },
    onChat: (msg) => setChatMessages((prev) => [...prev.slice(-99), msg]),
  });

  useEffect(() => {
    if (playerId && roomId) {
      stateQuery.refetch();
    }
  }, [playerId, roomId, stateQuery]);

  useEffect(() => {
    if (personas.length > 0 && !selectedAiId) {
      setSelectedAiId(personas[0].id);
    }
  }, [personas, selectedAiId]);

  useEffect(() => {
    const phase = stateQuery.data?.phase;
    if (!phase) {
      return;
    }
    if (phase !== prevPhaseRef.current && phase !== "WAITING") {
      setShowTransition(true);
      const timer = window.setTimeout(() => setShowTransition(false), 1800);
      prevPhaseRef.current = phase;
      return () => clearTimeout(timer);
    }
  }, [stateQuery.data?.phase]);

  const allRecoverableMessages = useMemo(
    () => [...DEFAULT_RECOVERABLE_MESSAGES, ...recoverableMessages],
    [recoverableMessages]
  );

  const handleActionError = useCallback(
    (error: unknown, fallback: string) => {
      const message = getApiErrorMessage(error, fallback);
      const recoverable = allRecoverableMessages.some((item) => message.includes(item));
      if (recoverable) {
        toast.info(message);
      } else {
        toast.error(message);
      }
      invalidateRoom();
    },
    [allRecoverableMessages, invalidateRoom]
  );

  const joinMutation = useMutation({
    mutationFn: () => {
      const password = room?.isPrivate ? window.prompt("请输入私密房间密码") || undefined : undefined;
      return roomApi.join(effectiveGameId, roomId || "", displayName, password);
    },
    onSuccess: (data) => {
      if (data.selfPlayerId && roomId) {
        invalidateRuntime();
      }
    },
    onError: (error: unknown) => toast.error(getApiErrorMessage(error, "加入房间失败")),
  });

  useEffect(() => {
    if (!room || loading || joinMutation.isSuccess || joinMutation.isPending || joinAttemptedRoomRef.current === room.id) {
      return;
    }
    if (!user) {
      void redirectToSsoLogin();
      return;
    }
    if (room.seats?.some((seat) => seat.playerId === user.id)) {
      return;
    }
    joinAttemptedRoomRef.current = room.id;
    joinMutation.mutate();
  }, [room, loading, token, playerId, user, redirectToSsoLogin, joinMutation]);

  const addAiMutation = useMutation({
    mutationFn: (personaId: string) => roomApi.addAi(effectiveGameId, roomId || "", personaId),
    onSuccess: () => {
      toast.success("AI 已加入");
      invalidateRoom();
    },
    onError: (error: unknown) => handleActionError(error, "添加 AI 失败"),
  });

  const state = stateQuery.data;
  const players = state?.players || [];
  const alivePlayers = useMemo(() => players.filter((p) => p.alive), [players]);
  const currentSpeaker = players.find((p) => p.seatNumber === state?.currentSeat);
  const phase = state?.phase || "WAITING";
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

    const archive = replayApi.fromState(effectiveGameId, room, state);
    replayApi.save(userKey, archive);
  }, [phase, state, userKey, roomId, effectiveGameId, room]);

  const startGame = useCallback(() => {
    startMutation.mutate(undefined, { onError: (error: unknown) => handleActionError(error, "开局失败") });
  }, [startMutation, handleActionError]);

  return {
    roomId,
    gameId: effectiveGameId,
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
    stateQuery,
    startMutation,
    actionMutation,
    addAiMutation,
    startGame,
    handleActionError,
    invalidateRuntime,
  };
}
