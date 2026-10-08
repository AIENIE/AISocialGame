import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { gameplayApi } from "@/services/api";
import { GameState, PlayerAction } from "@/types";

let requestSequence = 0;

export function prepareGameAction(action: PlayerAction, state?: GameState): PlayerAction {
  const phaseToken = state?.extra?.phaseToken;
  return {
    ...action,
    requestId: action.requestId || globalThis.crypto?.randomUUID?.()
      || `game-${Date.now().toString(36)}-${(++requestSequence).toString(36)}-${Math.random().toString(36).slice(2)}`,
    expectedPhaseToken: action.expectedPhaseToken || (typeof phaseToken === "string" ? phaseToken : undefined),
  };
}

export function useGameEngine(gameId: string | undefined, roomId: string | undefined) {
  const queryClient = useQueryClient();
  const queryKey = ["game-state", roomId];
  // A transport retry of the same logical submission must keep its identity and original phase.
  const preparedActions = useRef(new WeakMap<PlayerAction, PlayerAction>());
  const lastUnconfirmedAction = useRef<{ fingerprint: string; action: PlayerAction }>();

  const stateQuery = useQuery<GameState>({
    queryKey,
    queryFn: ({ signal }) => gameplayApi.state(gameId || "", roomId || "", signal),
    enabled: !!gameId && !!roomId,
    refetchInterval: 0,
  });

  const acceptState = async (state: GameState) => {
    await queryClient.cancelQueries({ queryKey });
    queryClient.setQueryData(queryKey, state);
    await queryClient.invalidateQueries({ queryKey });
  };

  const startMutation = useMutation({
    mutationFn: () => gameplayApi.start(gameId || "", roomId || ""),
    onSuccess: acceptState,
  });

  const actionMutation = useMutation({
    mutationFn: (action: PlayerAction) => {
      let prepared = preparedActions.current.get(action);
      if (!prepared) {
        const current = queryClient.getQueryData<GameState>(queryKey);
        const fingerprint = JSON.stringify([gameId, roomId, action.type, action.content || "", action.targetPlayerId || "",
          Boolean(action.abstain), action.nightAction || "", Boolean(action.useHeal), action.extra || {}, action.expectedPhaseToken || current?.extra?.phaseToken]);
        prepared = !action.requestId && lastUnconfirmedAction.current?.fingerprint === fingerprint
          ? lastUnconfirmedAction.current.action : prepareGameAction(action, current);
        lastUnconfirmedAction.current = { fingerprint, action: prepared };
        preparedActions.current.set(action, prepared);
      }
      return gameplayApi.action(gameId || "", roomId || "", prepared);
    },
    onSuccess: async (state, action) => {
      if (lastUnconfirmedAction.current?.action.requestId === preparedActions.current.get(action)?.requestId) lastUnconfirmedAction.current = undefined;
      await acceptState(state);
    },
  });

  return {
    stateQuery,
    startMutation,
    actionMutation,
    submitAction: (action: PlayerAction) => actionMutation.mutate(action),
    speak: (content: string) => actionMutation.mutate({ type: "SPEAK", content }),
    vote: (targetPlayerId: string, abstain = false) => actionMutation.mutate({ type: "VOTE", targetPlayerId, abstain }),
    nightAction: (nightAction: string, targetPlayerId?: string, useHeal = false) =>
      actionMutation.mutate({ type: "NIGHT_ACTION", nightAction, targetPlayerId, useHeal }),
  };
}
