import { ReactNode, useEffect, useRef } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { Button } from "@/components/ui/button";
import { CountdownTimer } from "@/components/game/CountdownTimer";
import { ConnectionStatusBar } from "@/components/game/ConnectionStatusBar";
import { useRoomText } from "./roomText";

export interface GameRoomFrameProps {
  gameId: string;
  title: string;
  stage: "waiting" | "playing" | "settlement";
  phaseText: string;
  task: string;
  round: number;
  phaseEndsAt?: string;
  connected: boolean;
  showReconnectAction: boolean;
  onReconnect: () => void;
  players: ReactNode;
  headerExtra?: ReactNode;
  menu?: ReactNode;
  context?: ReactNode;
  actions?: ReactNode;
  footer?: ReactNode;
  children: ReactNode;
}

export function GameRoomFrame(props: GameRoomFrameProps) {
  const copy = useRoomText();
  const frame = useRef<HTMLElement>(null);
  useEffect(() => {
    const viewport = window.visualViewport;
    const resize = () => frame.current?.style.setProperty("--room-viewport-height", `${viewport?.height || window.innerHeight}px`);
    resize(); viewport?.addEventListener("resize", resize); viewport?.addEventListener("scroll", resize);
    window.addEventListener("resize", resize);
    return () => { viewport?.removeEventListener("resize", resize); viewport?.removeEventListener("scroll", resize); window.removeEventListener("resize", resize); };
  }, []);
  return <section ref={frame} className="room-frame" data-stage={props.stage} data-testid="game-room">
    <header className="room-header">
      <Button asChild size="icon" variant="ghost" className="shrink-0"><Link to={`/game/${props.gameId}`} aria-label={copy("back")}><ArrowLeft className="h-5 w-5" /></Link></Button>
      <div className="min-w-0 flex-1"><h1 className="truncate text-sm font-semibold md:text-base">{props.title}</h1><p className="text-[11px] tracking-wide text-muted-foreground">NEXUS PLAY</p></div>
      {props.headerExtra}{props.menu}
    </header>
    {!props.connected && <ConnectionStatusBar connected={props.connected} showReconnectAction={props.showReconnectAction} onReconnect={props.onReconnect} />}
    <div className="room-status-strip" role="status">
      <div className="flex min-w-0 items-center gap-2"><span className="room-phase" data-testid="game-phase-text">{props.phaseText}</span>{props.round > 0 && <span className="shrink-0 text-xs text-muted-foreground">{copy("round", { n: props.round })}</span>}<span className="room-task">{props.task}</span></div>
      <CountdownTimer phaseEndsAt={props.phaseEndsAt} className="shrink-0" />
    </div>
    <div className="room-workspace">
      <aside className="room-roster"><div className="room-roster-heading">{copy("players")}</div>{props.players}</aside>
      <div className="room-center">{props.context}<div className="room-main">{props.children}</div>{props.actions}{props.footer}</div>
    </div>
  </section>;
}
