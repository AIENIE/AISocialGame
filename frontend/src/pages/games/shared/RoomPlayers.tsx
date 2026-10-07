import { Check, Plus, WifiOff } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import type { GamePlayerStateView } from "@/types";
import { useRoomText } from "./roomText";

interface Props {
  players: GamePlayerStateView[];
  myPlayerId?: string;
  currentSeat?: number;
  targets?: string[];
  selected?: string;
  onSelect: (id: string) => void;
  pending?: boolean;
  votedPlayers?: string[];
  ballot?: { votes: Record<string, string>; tally: Record<string, number>; round?: number };
  seatCount?: number;
  onAddSeat?: () => void;
  expanded?: boolean;
}

export function RoomPlayers({ players, myPlayerId, currentSeat, targets = [], selected, onSelect, pending,
  votedPlayers = [], ballot, seatCount, onAddSeat, expanded }: Props) {
  const copy = useRoomText();
  return <div className={`room-players ${expanded ? "room-players-expanded" : ""}`} aria-label={copy("players")}>
    {players.map(player => {
      const selectable = targets.includes(player.playerId), chosen = selected === player.playerId;
      const outgoing = ballot?.votes[player.playerId];
      const target = players.find(p => p.playerId === outgoing);
      const caption = outgoing ? outgoing === "abstain" ? copy("abstain") : copy("voteTo", { name: target ? copy("seat", { n: target.seatNumber + 1 }) : outgoing }) : "";
      return <button key={player.playerId} type="button" data-testid="game-player-card" data-player-id={player.playerId}
        data-is-me={player.playerId === myPlayerId} data-alive={player.alive} aria-pressed={selectable ? chosen : undefined}
        aria-disabled={!selectable || pending} title={`${player.displayName}${caption ? ` · ${copy("lastBallot")} · ${caption}` : ""}`}
        className={`room-player ${!player.alive ? "room-player-out" : ""} ${currentSeat === player.seatNumber ? "room-player-speaking" : ""} ${selectable ? "room-player-selectable" : ""} ${chosen ? "room-player-selected" : ""}`}
        onClick={() => { if (selectable && !pending) onSelect(player.playerId); }}>
        <div className="room-player-avatar"><Avatar className="h-9 w-9 shrink-0"><AvatarImage src={player.avatar} alt="" /><AvatarFallback>{player.displayName[0]}</AvatarFallback></Avatar>
          <span className="room-seat-number">{player.seatNumber + 1}</span>{chosen && <Check className="room-player-check" />}
        </div>
        <div className="room-player-name"><span className="block truncate font-medium">{player.displayName}{player.playerId === myPlayerId ? ` · ${copy("me")}` : ""}</span>
          <span className="room-player-status">
            {!player.alive ? copy("dead") : currentSeat === player.seatNumber ? copy("speaking") : player.connectionStatus === "AI_TAKEOVER" ? copy("managed") : player.connectionStatus === "DISCONNECTED" ? <><WifiOff className="inline h-3 w-3" /> {copy("disconnected")}</> : player.ai ? "AI" : copy("connected")}
          </span>
          {votedPlayers.includes(player.playerId) && <span className="room-player-vote">{copy("voted")}</span>}
          {ballot && <span className="room-player-ballot" aria-label={`${copy("lastBallot")} · ${copy("round", { n: ballot.round || 1 })}`}>{copy("votes", { n: ballot.tally[player.playerId] || 0 })}{caption && <span className="block truncate">{caption}</span>}</span>}
        </div>
      </button>;
    })}
    {onAddSeat && Array.from({ length: Math.max(0, (seatCount || players.length) - players.length) }, (_, index) =>
      <button type="button" key={`empty-${index}`} className="room-player room-empty-seat" onClick={onAddSeat} aria-label={copy("addSeat")}><Plus className="h-5 w-5" /><span>{copy("addSeat")}</span></button>)}
  </div>;
}
