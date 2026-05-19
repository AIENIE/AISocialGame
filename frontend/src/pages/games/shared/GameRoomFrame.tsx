import { ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { ChatPanel } from "@/components/game/ChatPanel";
import { ConnectionStatusBar } from "@/components/game/ConnectionStatusBar";
import { CountdownTimer } from "@/components/game/CountdownTimer";
import { PhaseTransition } from "@/components/game/PhaseTransition";
import { TutorialOverlay } from "@/components/tutorial/TutorialOverlay";
import { ChatMessage } from "@/types";

interface GameRoomFrameProps {
  connected: boolean;
  showReconnectAction: boolean;
  onReconnect: () => void;
  gameId: string;
  phase: string;
  showTransition: boolean;
  tutorialId: string;
  tutorialSteps: string[];
  title: string;
  phaseText: string;
  phaseEndsAt?: string;
  aliveCount: number;
  playerCount: number;
  headerExtra?: ReactNode;
  chatMessages: ChatMessage[];
  myPlayerId?: string;
  onSendChat: (type: ChatMessage["type"], content: string) => void;
  children: ReactNode;
}

export function GameRoomFrame({
  connected,
  showReconnectAction,
  onReconnect,
  gameId,
  phase,
  showTransition,
  tutorialId,
  tutorialSteps,
  title,
  phaseText,
  phaseEndsAt,
  aliveCount,
  playerCount,
  headerExtra,
  chatMessages,
  myPlayerId,
  onSendChat,
  children,
}: GameRoomFrameProps) {
  return (
    <>
      <ConnectionStatusBar connected={connected} showReconnectAction={showReconnectAction} onReconnect={onReconnect} />
      <PhaseTransition gameId={gameId} phase={phase} visible={showTransition} />
      <TutorialOverlay id={tutorialId} steps={tutorialSteps} />

      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-xl font-bold">{title}</h2>
            <p className="text-sm text-muted-foreground" data-testid="game-phase-text">
              {phaseText}
            </p>
          </div>
          <div className="flex items-center gap-2">
            {headerExtra}
            <CountdownTimer phaseEndsAt={phaseEndsAt} />
            <Badge variant="secondary">
              {aliveCount}/{playerCount} 存活
            </Badge>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-4">
          <div className="space-y-4 xl:col-span-3">{children}</div>

          <ChatPanel messages={chatMessages} myPlayerId={myPlayerId} onSend={onSendChat} />
        </div>
      </div>
    </>
  );
}
