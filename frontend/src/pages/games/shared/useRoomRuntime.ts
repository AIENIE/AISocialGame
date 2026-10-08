import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { useAuth } from "@/hooks/useAuth";
import { useGameEngine } from "@/hooks/useGameEngine";
import { useGameSocket } from "@/hooks/useGameSocket";
import { getApiErrorMessage, getApiErrorCode, isRecoverableGameError, personaApi, roomApi } from "@/services/api";
import { localizeErrorMessage } from "@/i18n/errors";
import { ChatMessage, GameStateEvent, PrivateEvent } from "@/types";

const DEFAULT_RECOVERABLE_MESSAGES = ["已完成投票", "当前不需要你发言", "房间已满", "当前阶段不支持该操作"];

interface UseRoomRuntimeOptions {
  defaultGameId: string;
  recoverableMessages?: string[];
}

export function useRoomRuntime({ defaultGameId, recoverableMessages = [] }: UseRoomRuntimeOptions) {
  const { t } = useTranslation();
  const { roomId, gameId } = useParams();
  const effectiveGameId = gameId || defaultGameId;
  const queryClient = useQueryClient();
  const { user, token, loading } = useAuth();
  const playerId = user?.id || null;
  const [selectedAiId, setSelectedAiId] = useState<string>("");
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);

  const lastSocketVersionRef = useRef<string | null>(null);
  const userKey = user?.id || "";

  const personaQuery = useQuery({
    queryKey: ["personas"],
    queryFn: personaApi.list,
  });

  const personas = personaQuery.data ?? [];

  const roomQuery = useQuery({
    queryKey: ["room", roomId],
    queryFn: ({ signal }) => roomApi.detail(effectiveGameId, roomId || "", signal),
    enabled: !!roomId && !!effectiveGameId,
  });
  const room = roomQuery.data;

  const { stateQuery, startMutation, actionMutation } = useGameEngine(effectiveGameId, user ? roomId : undefined);
  useEffect(() => {
    if ([roomQuery.error, stateQuery.error].some(error => error && getApiErrorCode(error) === "ROOM_EXPIRED")) {
      window.dispatchEvent(new CustomEvent("room-expired", { detail: roomId }));
    }
  }, [roomQuery.error, stateQuery.error, roomId]);
  const { refetch: refetchState } = stateQuery;

  const invalidateRuntime = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ["game-state", roomId] });
  }, [queryClient, roomId]);

  const invalidateSocketVersion = useCallback((event: GameStateEvent | PrivateEvent) => {
    const version = event?.payload?.viewVersion;
    if (typeof version === "string" && version) {
      if (lastSocketVersionRef.current === `${roomId}:${version}`) return;
      lastSocketVersionRef.current = `${roomId}:${version}`;
    }
    invalidateRuntime();
  }, [invalidateRuntime, roomId]);

  const invalidateRoom = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ["room-entry", effectiveGameId, roomId] });
    queryClient.invalidateQueries({ queryKey: ["room", roomId] });
    queryClient.invalidateQueries({ queryKey: ["game-state", roomId] });
  }, [queryClient, roomId, effectiveGameId]);

  const socket = useGameSocket({
    roomId,
    playerId,
    token,
    onConnected: invalidateRoom,
    onStateChange: invalidateSocketVersion,
    onSeatChange: invalidateRoom,
    onPrivate: (event) => {
      if (event.type === "ROOM_EXPIRED" && event.payload?.roomId === roomId) {
        window.dispatchEvent(new CustomEvent("room-expired", { detail: roomId }));
        return;
      }
      if (event.type === "SAFETY_NOTICE") {
        const message = typeof event.payload?.message === "string" ? event.payload.message : "";
        toast.warning(localizeErrorMessage(message, "errors.contentBlocked"));
      }
      invalidateSocketVersion(event);
    },
    onChat: (msg) => setChatMessages((prev) => prev.some(item => item.id === msg.id) ? prev : [...prev.slice(-99), msg]),
  });

  useEffect(() => { setChatMessages([]); }, [roomId, playerId]);

  useEffect(() => {
    if (playerId && roomId) {
      refetchState();
    }
  }, [playerId, roomId, refetchState]);

  useEffect(() => {
    if (!personas.some(p => p.id === selectedAiId)) {
      setSelectedAiId(personas[0]?.id ?? "");
    }
  }, [personas, selectedAiId]);

  useEffect(() => {
    if (stateQuery.data?.phase === "SETTLEMENT") {
      void queryClient.invalidateQueries({ queryKey: ["room-entry", effectiveGameId, roomId] });
      void queryClient.invalidateQueries({ queryKey: ["room", roomId] });
    }
  }, [stateQuery.data?.phase, roomId, queryClient, effectiveGameId]);

  const allRecoverableMessages = useMemo(
    () => [...DEFAULT_RECOVERABLE_MESSAGES, ...recoverableMessages],
    [recoverableMessages]
  );

  const handleActionError = useCallback(
    (error: unknown, fallbackKey: string) => {
      const raw = getApiErrorMessage(error, "");
      const code = getApiErrorCode(error);
      if (code === "ROOM_EXPIRED") window.dispatchEvent(new CustomEvent("room-expired", { detail: roomId }));
      const recoverable = code ? isRecoverableGameError(code) : allRecoverableMessages.some((item) => raw.includes(item));
      const message = localizeErrorMessage(raw, fallbackKey, code);
      if (recoverable) {
        toast.info(message);
      } else {
        toast.error(message);
      }
      invalidateRoom();
    },
    [allRecoverableMessages, invalidateRoom, roomId]
  );

  const addAiMutation = useMutation({
    mutationFn: (personaId: string) => roomApi.addAi(effectiveGameId, roomId || "", personaId),
    onSuccess: (updatedRoom) => {
      queryClient.setQueryData(["room", roomId], updatedRoom);
      toast.success(t("lobby.aiSeated"));
      invalidateRoom();
    },
    onError: (error: unknown) => handleActionError(error, "lobby.addAiFailed"),
  });

  const state = stateQuery.data;
  const players = state?.players || [];
  const alivePlayers = useMemo(() => players.filter((p) => p.alive), [players]);
  const currentSpeaker = players.find((p) => p.seatNumber === state?.currentSeat);
  const phase = state?.phase || "WAITING";
  const isHost = room?.hostUserId === playerId || !!room?.seats?.some((seat) => seat.playerId === playerId && seat.host);
  const canAddAi = isHost && room?.status === "WAITING" && !personaQuery.isPending && !personaQuery.isError
    && !addAiMutation.isPending && personas.some(p => p.id === selectedAiId) && (room?.seats?.length ?? 0) < (room?.maxPlayers ?? 0);

  const startGame = useCallback(() => {
    startMutation.mutate(undefined, { onError: (error: unknown) => handleActionError(error, "errors.startFailed") });
  }, [startMutation, handleActionError]);

  return {
    roomId,
    gameId: effectiveGameId,
    room,
    roomQuery,
    authLoading: loading,
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
