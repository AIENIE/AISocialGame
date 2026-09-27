import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { FriendItem, GameState } from "@/types";
import { friendApi } from "@/services/v2Social";
import { toast } from "sonner";
import { UserPlus } from "lucide-react";
import { gameName } from "@/i18n/gameTexts";

interface SettlementPanelProps {
  gameId?: string;
  state: GameState;
  userKey: string;
}

export const SettlementPanel = ({ gameId, state, userKey }: SettlementPanelProps) => {
  const { t } = useTranslation();
  const cards = useMemo(() => state.players || [], [state.players]);

  return (
    <Card data-testid="game-settlement-panel" className="space-y-4 border-purple-200 bg-gradient-to-br from-violet-50 via-slate-50 to-amber-50 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-lg font-semibold">{t("settle.title")}</h3>
          <p className="text-sm text-muted-foreground">
            {t("settle.summary", {
              game: gameName(gameId, gameId || ""),
              winner: state.winner || t("common.undetermined"),
            })}
          </p>
        </div>
        <Badge className="bg-purple-600">{state.winner || t("settle.settling")}</Badge>
      </div>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
        {cards.map((player) => (
          <div key={player.playerId} className="rounded-lg border bg-white/90 p-3">
            <div className="flex items-center gap-2">
              <Avatar className="h-9 w-9">
                <AvatarImage src={player.avatar} />
                <AvatarFallback>{player.displayName.slice(0, 1)}</AvatarFallback>
              </Avatar>
              <div className="min-w-0 flex-1">
                <div className="truncate font-medium">{player.displayName}</div>
                <div className="text-xs text-muted-foreground">{t("game.seat", { seat: player.seatNumber + 1 })}</div>
              </div>
              <Badge variant={player.alive ? "secondary" : "destructive"}>{player.alive ? t("settle.alive") : t("game.out")}</Badge>
            </div>
            <div className="mt-2 flex flex-wrap gap-2 text-xs">
              {player.role && <Badge variant="outline">{player.role}</Badge>}
              {(player as any).word && <Badge variant="outline">{(player as any).word}</Badge>}
            </div>
            {player.playerId !== state.myPlayerId && !player.ai && (
              <Button
                size="sm"
                variant="outline"
                className="mt-3 w-full"
                onClick={() => {
                  const target: FriendItem = {
                    id: player.playerId,
                    displayName: player.displayName,
                    avatar: player.avatar,
                    online: false,
                  };
                  if (!friendApi.sendFriendRequest(userKey, target)) { toast.error(t("errors.localStorageUnavailable")); return; }
                  toast.success(t("settle.friendSent", { name: player.displayName }));
                }}
              >
                <UserPlus className="mr-1 h-3 w-3" />
                {t("settle.addFriend")}
              </Button>
            )}
          </div>
        ))}
      </div>
    </Card>
  );
};
