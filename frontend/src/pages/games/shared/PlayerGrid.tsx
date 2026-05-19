import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { GamePlayerStateView } from "@/types";
import { WifiOff } from "lucide-react";

interface PlayerGridProps {
  players: GamePlayerStateView[];
  myPlayerId?: string;
  selectedPlayerId?: string | null;
  currentSeat?: number;
  phase: string;
  votingPhase: string;
  speakingPhase: string;
  onSelectPlayer: (playerId: string) => void;
}

export function PlayerGrid({
  players,
  myPlayerId,
  selectedPlayerId,
  currentSeat,
  phase,
  votingPhase,
  speakingPhase,
  onSelectPlayer,
}: PlayerGridProps) {
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
      {players.map((p) => (
        <div
          key={p.playerId}
          data-testid="game-player-card"
          data-player-id={p.playerId}
          data-is-me={p.playerId === myPlayerId ? "true" : "false"}
          data-alive={p.alive ? "true" : "false"}
          className={`flex items-center gap-3 rounded-lg border p-2 transition ${p.alive ? "border-slate-200" : "border-red-200 bg-red-50"} ${
            selectedPlayerId === p.playerId ? "ring-2 ring-amber-400" : ""
          } ${phase === votingPhase && p.playerId !== myPlayerId ? "cursor-pointer hover:bg-amber-50" : ""}`}
          onClick={() => {
            if (phase !== votingPhase || p.playerId === myPlayerId || !p.alive) return;
            onSelectPlayer(p.playerId);
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
          {p.connectionStatus === "AI_TAKEOVER" && (
            <Badge variant="outline" className="text-amber-600">
              托管
            </Badge>
          )}
          {currentSeat === p.seatNumber && phase === speakingPhase && <Badge variant="outline">发言中</Badge>}
        </div>
      ))}
    </div>
  );
}
