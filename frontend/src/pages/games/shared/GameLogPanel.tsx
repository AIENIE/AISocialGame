import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { GameLogEntry } from "@/types";

interface GameLogPanelProps {
  logs?: GameLogEntry[];
  emptyText: string;
}

export function GameLogPanel({ logs = [], emptyText }: GameLogPanelProps) {
  return (
    <Card className="p-4" data-testid="game-logs-panel">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="font-semibold">游戏日志</h3>
        <Badge variant="outline">实时</Badge>
      </div>
      <ScrollArea className="h-64 pr-2">
        <div className="space-y-2 text-sm">
          {logs.map((log, idx) => (
            <div key={idx} data-testid="game-log-item" className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground">{new Date(log.time).toLocaleTimeString()}</span>
              <span>{log.message}</span>
            </div>
          ))}
          {logs.length === 0 && (
            <div data-testid="game-log-empty" className="text-sm text-muted-foreground">
              {emptyText}
            </div>
          )}
        </div>
      </ScrollArea>
    </Card>
  );
}
