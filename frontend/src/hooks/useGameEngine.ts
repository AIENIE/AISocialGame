import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { gameplayApi } from "@/services/api";
import { GameState, PlayerAction } from "@/types";

export function useGameEngine(gameId: string | undefined, roomId: string | undefined, playerId?: string) {
  const queryClient = useQueryClient();
  const queryKey = ["game-state", roomId];

  const stateQuery = useQuery<GameState>({
    queryKey,
    queryFn: () => gameplayApi.state(gameId || "", roomId || "", playerId),
    enabled: !!gameId && !!roomId,
    refetchInterval: 0,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey });

  const startMutation = useMutation({
    mutationFn: () => gameplayApi.start(gameId || "", roomId || "", playerId),
    onSuccess: invalidate,
  });

  const actionMutation = useMutation({
    mutationFn: (action: PlayerAction) => gameplayApi.action(gameId || "", roomId || "", action, playerId),
    onSuccess: invalidate,
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
