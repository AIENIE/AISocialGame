import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { gameplayApi, roomApi } from "@/services/api";
import { DataState } from "@/components/DataState";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Eye } from "lucide-react";
const SpectatorRoom = () => {
  const { t } = useTranslation();
  const { gameId, roomId } = useParams();
  const room = useQuery({ queryKey: ["room", roomId], queryFn: () => roomApi.detail(gameId || "", roomId || ""), enabled: !!gameId && !!roomId });
  const state = useQuery({ queryKey: ["spectator-state", gameId, roomId], queryFn: () => gameplayApi.state(gameId || "", roomId || ""), enabled: !!gameId && !!roomId, refetchInterval: 2000 });
  const error = room.error || state.error;
  if (room.isPending || state.isPending || error || !room.data || !state.data) return <DataState loading={room.isPending || state.isPending} error={error} onRetry={() => { void room.refetch(); void state.refetch(); }} />;
  const players = state.data.players;
  return <div className="mx-auto max-w-6xl space-y-4"><Card><CardHeader><CardTitle className="flex items-center gap-2"><Eye className="h-5 w-5" />{t("spectate.title")}</CardTitle></CardHeader>
    <CardContent className="flex flex-wrap gap-2"><Badge>{room.data.name}</Badge><Badge variant="outline">{t("spectate.phase", { phase: state.data.phase })}</Badge><Badge variant="outline">{t("spectate.alive", { alive: players.filter(player => player.alive).length, total: players.length })}</Badge><span className="text-sm text-muted-foreground">{t("spectate.notice")}</span></CardContent>
    </Card><Card><CardHeader><CardTitle>{t("spectate.playerView")}</CardTitle></CardHeader><CardContent><div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
      {players.map(player => <div key={player.playerId} className="flex items-center gap-2 rounded border p-3"><Avatar><AvatarImage src={player.avatar} /><AvatarFallback>{player.displayName.slice(0, 1)}</AvatarFallback></Avatar><div><p>{player.displayName}</p><p className="text-xs text-muted-foreground">{t("game.seat", { seat: player.seatNumber + 1 })}</p></div>{!player.alive && <Badge variant="destructive">{t("game.out")}</Badge>}{player.role && <Badge variant="outline">{player.role}</Badge>}</div>)}
      {!players.length && <DataState empty />}
    </div></CardContent></Card></div>;
};
export default SpectatorRoom;
