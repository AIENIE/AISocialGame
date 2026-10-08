import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { RoomNumber } from "@/components/rooms/RoomEntry";
import { DataState } from "@/components/DataState";
import { BookOpen, History, LockKeyhole, MessageSquare, MoreHorizontal, Play, Share2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { LanguageMenuItems } from "@/i18n/LanguageSelector";
import { ChatPanel } from "@/components/game/ChatPanel";
import { SettlementPanel } from "@/components/game/SettlementPanel";
import type { GamePlayerStateView, GameState, Room } from "@/types";
import { PersonaPicker } from "./PersonaPicker";
import { useRoomRuntime } from "./useRoomRuntime";
import { GameRoomFrame } from "./GameRoomFrame";
import { RoomPlayers } from "./RoomPlayers";
import { GameTimeline } from "./GameTimeline";
import { useRoomTimeline } from "./useRoomTimeline";
import { revealedBallot } from "./roomEvents";
import { RoomActionDock } from "./RoomActionDock";
import { useRoomActionController } from "./useRoomActionController";
import { GameContext, PrivateActionContext, PrivateDetails, RoomRules } from "./RoomGameDetails";
import { privateLabel } from "./roomIdentity";
import { roomPhase, useRoomText } from "./roomText";
import "./room.css";

type Runtime = ReturnType<typeof useRoomRuntime>;
const RECOVERABLE = ["你已出局，无法行动"];

export function RoomExperience({ gameId }: { gameId: string }) {
  const runtime = useRoomRuntime({ defaultGameId: gameId, recoverableMessages: RECOVERABLE });
  const copy = useRoomText();
  const error = runtime.roomQuery.error || runtime.stateQuery.error;
  if (error || !runtime.room || !runtime.state) return <div className="flex min-h-dvh flex-col items-center justify-center gap-4 p-6">
    <DataState loading={runtime.roomQuery.isPending || runtime.stateQuery.isPending} error={error} onRetry={() => { void runtime.roomQuery.refetch(); void runtime.stateQuery.refetch(); }} />
    <Button asChild variant="ghost"><Link to={`/game/${gameId}`}>{copy("back")}</Link></Button>
  </div>;
  return <RoomScene key={`${runtime.room.id}:${runtime.state.extra?.archiveId || "waiting"}:${runtime.userKey}`} runtime={runtime} room={runtime.room} state={runtime.state} />;
}

export function RoomScene({ runtime, room, state }: { runtime: Runtime; room: Room; state: GameState }) {
  const { t, i18n } = useTranslation();
  const copy = useRoomText();
  const stage = state.phase === "SETTLEMENT" ? "settlement" : state.phase === "WAITING" ? "waiting" : "playing";
  const [drawer, setDrawer] = useState<"history" | "private" | "rules" | "setup" | "chat" | "players" | null>(null);
  const seenChat = useRef(runtime.chatMessages.at(-1)?.id);
  const [unread, setUnread] = useState(0);
  useEffect(() => {
    const last = runtime.chatMessages.at(-1)?.id;
    if (stage !== "playing" || drawer === "chat") { seenChat.current = last; setUnread(0); }
    else if (last && last !== seenChat.current) {
      const index = runtime.chatMessages.findIndex(item => item.id === seenChat.current);
      setUnread(index < 0 ? runtime.chatMessages.length : runtime.chatMessages.length - index - 1);
    }
  }, [runtime.chatMessages, stage, drawer]);
  useEffect(() => { if (stage === "playing") setDrawer(null); }, [stage]);
  const timeline = useRoomTimeline(state);
  const control = useRoomActionController(state, async action => {
    try { return await runtime.actionMutation.mutateAsync(action); }
    catch (error) { runtime.handleActionError(error, "game.submitSpeakFailed"); throw error; }
  });
  const playing = stage === "playing";
  const players: GamePlayerStateView[] = stage === "waiting" ? room.seats.map(seat => ({ ...seat, alive: true })) : state.players;
  const me = players.find(player => player.playerId === state.myPlayerId);
  const voted = state.extra?.myVote || (Array.isArray(state.extra?.votedPlayers) && state.extra.votedPlayers.includes(state.myPlayerId));
  const waiting = !me?.alive && me ? copy("deadWait") : voted ? copy("submitted") : runtime.currentSpeaker ? copy("listening", { name: runtime.currentSpeaker.displayName }) : copy("waiting");
  const task = stage === "waiting" ? copy(runtime.isHost ? "prepare" : "hostStarts") : playing ? control.capabilities.length ? copy("yourTurn") : waiting : "";
  const label = privateLabel(state, room, i18n.language);
  const ballot = revealedBallot(state, timeline.logs);
  const roster = (expanded = false) => <RoomPlayers players={players} myPlayerId={state.myPlayerId}
    currentSeat={playing ? state.currentSeat : undefined} selected={control.target} targets={control.selected?.targets}
    onSelect={id => { control.selectTarget(id); if (expanded) setDrawer(null); }} pending={control.busy || !runtime.socket.connected}
    votedPlayers={Array.isArray(state.extra?.votedPlayers) ? state.extra.votedPlayers : []} ballot={stage === "waiting" ? undefined : ballot}
    seatCount={room.maxPlayers} onAddSeat={stage === "waiting" ? () => setDrawer("setup") : undefined} expanded={expanded} />;
  const sendChat = (type: "TEXT" | "EMOJI" | "QUICK_PHRASE", content: string) => {
    const sent = runtime.socket.sendChat(type, content);
    if (!sent) toast.error(t("game.chat.sendFailed"));
    return sent;
  };
  const chat = (readOnly = false) => <ChatPanel messages={runtime.chatMessages} myPlayerId={state.myPlayerId} onSend={sendChat} readOnly={readOnly} disabled={!runtime.socket.connected} />;
  const invite = async () => {
    try { await navigator.clipboard.writeText(window.location.href); toast.success(copy("copied")); }
    catch { toast.error(copy("copyFailed")); }
  };
  const minPlayers = state.gameId === "werewolf" ? Number(room.config?.playerCount || room.maxPlayers) : state.gameId === "undercover" ? 4 : 1;
  const remaining = Math.max(0, minPlayers - room.seats.length);
  const start = <div className="room-start-bar"><span className="text-sm text-muted-foreground">{remaining ? copy("needPlayers", { n: remaining }) : !runtime.isHost ? copy("hostStarts") : copy("prepare")}</span>
    <Button data-testid="game-start-btn" className="room-submit" disabled={!runtime.isHost || remaining > 0 || runtime.startMutation.isPending || !runtime.socket.connected} onClick={runtime.startGame}><Play className="mr-1 h-4 w-4" />{stage === "settlement" ? copy("restart") : t("lobby.startGame")}</Button></div>;
  const setup = <div className="space-y-5"><Button variant="outline" onClick={() => void invite()}><Share2 className="mr-2 h-4 w-4" />{copy("invite")}</Button>
    <PersonaPicker personas={runtime.personas} selectedAiId={runtime.selectedAiId} onSelectedAiIdChange={runtime.setSelectedAiId} canAddAi={runtime.canAddAi && !playing}
      onAddAi={() => runtime.addAiMutation.mutate(runtime.selectedAiId)} isHost={runtime.isHost} isWaiting={!playing} full={room.seats.length >= room.maxPlayers}
      isLoading={runtime.personaQuery.isPending} isError={runtime.personaQuery.isError} onRetry={() => void runtime.personaQuery.refetch()}
      isAdding={runtime.addAiMutation.isPending} addError={runtime.addAiMutation.isError} />
    <details><summary className="cursor-pointer text-sm font-semibold">{copy("rules")}</summary><div className="mt-3"><RoomRules state={state} room={room} /></div></details>
  </div>;
  const timelineView = (history = false) => <GameTimeline logs={timeline.logs} players={players} myPlayerId={state.myPlayerId} history={history}
    hasMore={timeline.hasMore} loading={timeline.loading} error={timeline.error} onLoadOlder={timeline.loadOlder} onRetry={timeline.retry} />;
  const canReact = playing && me?.alive && !["NIGHT", "DEATH_ACTION"].includes(state.phase);
  return <>
    <GameRoomFrame gameId={state.gameId} title={room.name} stage={stage} phaseText={roomPhase(state.phase, i18n.language)} task={task} round={state.round}
      phaseEndsAt={state.phaseEndsAt || state.extra?.actionEndsAt} connected={runtime.socket.connected} showReconnectAction={runtime.socket.showReconnectAction} onReconnect={runtime.socket.reconnect}
      players={roster()} headerExtra={playing && label ? <Button variant="secondary" size="sm" className="room-private-chip" onClick={() => setDrawer("private")} aria-label={`${copy("details")} · ${label}`}><LockKeyhole className="mr-1 h-3 w-3 shrink-0" /><span className="truncate" data-testid="undercover-private-word">{label}</span></Button> : undefined}
      menu={<><Button size="icon" variant="ghost" aria-label={copy("history")} onClick={() => setDrawer("history")}><History className="h-4 w-4" /></Button>
        <DropdownMenu><DropdownMenuTrigger asChild><Button size="icon" variant="ghost" aria-label={copy("more")}><MoreHorizontal className="h-5 w-5" /></Button></DropdownMenuTrigger>
          <DropdownMenuContent align="end"><RoomNumber code={room.roomCode} /><DropdownMenuItem onClick={() => setDrawer("players")}>{copy("players")}</DropdownMenuItem>
            {playing ? <DropdownMenuItem onClick={() => setDrawer("private")}>{copy(state.gameId === "turtle_soup" ? "clues" : "details")}</DropdownMenuItem> : <><DropdownMenuItem onClick={() => setDrawer("setup")}>{copy(stage === "settlement" ? "nextSetup" : "addSeat")}</DropdownMenuItem><DropdownMenuItem onClick={() => setDrawer("rules")}>{copy("rules")}</DropdownMenuItem></>}
            <DropdownMenuSeparator /><LanguageMenuItems /></DropdownMenuContent>
        </DropdownMenu></>}
      context={playing ? <GameContext state={state} /> : stage === "waiting" ? <div className="room-prepare-context"><div><h2 className="font-semibold">{copy("prepare")}</h2><RoomNumber code={room.roomCode} /><p className="text-xs text-muted-foreground">{t("rooms.waitRule")}</p><p className="mt-1 text-sm text-muted-foreground">{room.seats.length} / {room.maxPlayers}</p></div><Button variant="outline" size="sm" onClick={() => setDrawer("setup")}><Share2 className="mr-1 h-4 w-4" />{copy("addSeat")}</Button><details><summary>{copy("rules")}</summary><RoomRules state={state} room={room} /></details></div> : undefined}
      actions={playing ? <>
        {canReact && <div className="room-reactions" aria-label={copy("reaction")}>{["👍", "🤔", "😂", "😱"].map(emoji => <button type="button" key={emoji} aria-label={`${copy("reaction")} ${emoji}`} disabled={!runtime.socket.connected} onClick={() => sendChat("EMOJI", emoji)}>{emoji}</button>)}</div>}
        <RoomActionDock state={state} control={control} connected={runtime.socket.connected} waitingText={waiting} onChoosePlayer={() => setDrawer("players")}
          context={control.selected?.type === "NIGHT_ACTION" ? <PrivateActionContext state={state} /> : undefined} />
      </> : start}
      footer={playing ? <button type="button" className="room-chat-collapsed" onClick={() => setDrawer("chat")} data-testid="room-chat-toggle"><MessageSquare className="h-3.5 w-3.5" /><span>{copy("roomChat")}</span><span className="ml-auto">{unread ? copy("unread", { n: unread }) : copy("readOnly")}</span></button> : undefined}>
      {playing ? timelineView() : stage === "waiting" ? chat() : <div className="room-settlement">
        <div className="room-settlement-results">
          {state.gameId === "turtle_soup" && <div className="mb-4 space-y-2"><h2 className="font-semibold">{t("game.solutionTitle")}</h2><p className="whitespace-pre-wrap text-sm leading-6">{String(state.extra?.solution || "")}</p></div>}
          <SettlementPanel gameId={state.gameId} state={state} />
          <div className="mt-3 flex flex-wrap gap-2">{state.extra?.archiveId && <Button asChild variant="outline" size="sm"><Link to={`/replay/${state.extra.archiveId}`}><BookOpen className="mr-1 h-4 w-4" />{copy("replay")}</Link></Button>}<Button size="sm" variant="outline" onClick={() => setDrawer("setup")}>{copy("nextSetup")}</Button></div>
        </div><div className="room-settlement-chat">{chat()}</div>
      </div>}
    </GameRoomFrame>
    <Sheet open={!!drawer} onOpenChange={open => { if (!open) setDrawer(null); }}>
      <SheetContent side="right" className={`room-drawer ${drawer === "history" ? "room-history-drawer" : ""}`}>
        <SheetHeader><SheetTitle>{copy(drawer === "private" ? state.gameId === "turtle_soup" ? "clues" : "details" : drawer === "setup" ? stage === "settlement" ? "nextSetup" : "addSeat" : drawer === "chat" ? "roomChat" : drawer || "details")}</SheetTitle><SheetDescription>{drawer === "chat" ? copy("readOnly") : drawer === "private" && state.gameId !== "turtle_soup" ? copy("private") : room.name}</SheetDescription></SheetHeader>
        <div className={`room-drawer-content ${drawer === "history" || drawer === "chat" ? "room-drawer-flex" : ""}`}>
          {drawer === "history" && timelineView(true)}{drawer === "private" && <PrivateDetails state={state} />}{drawer === "rules" && !playing && <RoomRules state={state} room={room} />}
          {drawer === "setup" && !playing && setup}{drawer === "chat" && chat(true)}{drawer === "players" && <>{roster(true)}{control.selected && <p className="mt-4 text-sm text-muted-foreground">{copy("choose")} → {copy("confirm")}</p>}</>}
        </div>
      </SheetContent>
    </Sheet>
  </>;
}
