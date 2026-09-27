import { useTranslation } from "react-i18next";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { GameLogEntry, GamePlayerStateView, Persona } from "@/types";
import { WifiOff } from "lucide-react";
import { latestPublicPresentations, presentationText } from "./aiPresentation";

import { PersonaBadge } from "./PersonaPicker";

interface PlayerGridProps {
  personas?: Persona[];
  players: GamePlayerStateView[];
  logs?: GameLogEntry[];
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
  personas = [],
  logs = [],
  myPlayerId,
  selectedPlayerId,
  currentSeat,
  phase,
  votingPhase,
  speakingPhase,
  onSelectPlayer,
}: PlayerGridProps) {
  const { t, i18n } = useTranslation();
  const presentations = latestPublicPresentations(logs, phase);
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
      {players.map((p) => {
        const selectable = phase === votingPhase && p.playerId !== myPlayerId && p.alive;
        const cue = presentationText(presentations.get(p.playerId) || p.presentation, i18n.language);
        return (
        <div
          key={p.playerId}
          data-testid="game-player-card"
          data-player-id={p.playerId}
          data-is-me={p.playerId === myPlayerId ? "true" : "false"}
          data-alive={p.alive ? "true" : "false"}
          role={selectable ? "button" : undefined}
          tabIndex={selectable ? 0 : undefined}
          aria-pressed={selectable ? selectedPlayerId === p.playerId : undefined}
          className={`flex items-center gap-3 rounded-lg border p-2 transition motion-reduce:transition-none ${p.alive ? "border-border" : "border-red-200 bg-red-50 dark:border-red-900 dark:bg-red-950/20"} ${
            selectedPlayerId === p.playerId ? "ring-2 ring-amber-400" : ""
          } ${selectable ? "cursor-pointer hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" : ""}`}
          onClick={() => {
            if (phase !== votingPhase || p.playerId === myPlayerId || !p.alive) return;
            onSelectPlayer(p.playerId);
          }}
          onKeyDown={(event) => {
            if (!selectable || (event.key !== "Enter" && event.key !== " ")) return;
            event.preventDefault(); onSelectPlayer(p.playerId);
          }}
        >
          <Avatar className="h-10 w-10">
            <AvatarImage src={p.avatar} alt={p.displayName} />
            <AvatarFallback>{p.displayName[0]}</AvatarFallback>
          </Avatar>
          <div className="min-w-0 flex-1">
            <div className="truncate font-medium">{p.displayName}</div>
            {p.ai && <PersonaBadge personaId={p.personaId} personas={personas} />}
            <div className="text-xs text-muted-foreground">{t("game.seat", { seat: p.seatNumber + 1 })}</div>
            {cue && <div data-testid="player-presentation" className="mt-1 text-xs leading-4 text-muted-foreground">{cue}</div>}
          </div>
          {!p.alive && <Badge variant="destructive">{t("game.out")}</Badge>}
          {p.connectionStatus === "DISCONNECTED" && <WifiOff className="h-4 w-4 text-slate-400" />}
          {p.connectionStatus === "AI_TAKEOVER" && (
            <Badge variant="outline" className="text-amber-600">
              {t("game.managed")}
            </Badge>
          )}
          {currentSeat === p.seatNumber && phase === speakingPhase && <Badge variant="outline">{t("game.speaking")}</Badge>}
        </div>
        );
      })}
    </div>
  );
}
