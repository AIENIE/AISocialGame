import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { gameplayApi, roomApi } from "@/services/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Eye, Send } from "lucide-react";
import { toast } from "sonner";
import { useAuth } from "@/hooks/useAuth";

interface SpectatorChatMessage {
  id: string;
  sender: string;
  content: string;
  time: string;
}

const SpectatorRoom = () => {
  const { gameId, roomId } = useParams();
  const { user, loading, redirectToSsoLogin } = useAuth();
  const [chatInput, setChatInput] = useState("");
  const [chatMessages, setChatMessages] = useState<SpectatorChatMessage[]>([]);

  const { data: room } = useQuery({
    queryKey: ["room", roomId],
    queryFn: () => roomApi.detail(gameId || "", roomId || ""),
    enabled: !!gameId && !!roomId,
  });

  const { data: state, isLoading } = useQuery({
    queryKey: ["spectator-state", gameId, roomId],
    queryFn: () => gameplayApi.state(gameId || "", roomId || ""),
    enabled: !!gameId && !!roomId && !!user,
    refetchInterval: 2000,
  });

  useEffect(() => {
    if (!loading && !user) {
      void redirectToSsoLogin();
    }
  }, [loading, user, redirectToSsoLogin]);

  const players = state?.players || [];
  const aliveCount = useMemo(() => players.filter((p) => p.alive).length, [players]);

  return (
    <div className="mx-auto max-w-6xl space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <Eye className="h-4 w-4" />
            观战模式
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-2 text-sm">
          <Badge variant="secondary">{room?.name || "房间"}</Badge>
          <Badge variant="outline">阶段: {state?.phase || "--"}</Badge>
          <Badge variant="outline">
            存活 {aliveCount}/{players.length}
          </Badge>
          <span className="text-muted-foreground">观战中无法执行发言/投票/夜晚行动。</span>
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader className="pb-3">
            <CardTitle className="text-base">玩家视图（上帝视角）</CardTitle>
          </CardHeader>
          <CardContent>
            {isLoading && <div className="text-sm text-muted-foreground">正在同步房间状态...</div>}
            {!isLoading && (
              <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
                {players.map((player) => (
                  <div key={player.playerId} className={`rounded-md border p-2 ${player.alive ? "" : "bg-red-50"}`}>
                    <div className="flex items-center gap-2">
                      <Avatar className="h-8 w-8">
                        <AvatarImage src={player.avatar} />
                        <AvatarFallback>{player.displayName.slice(0, 1)}</AvatarFallback>
                      </Avatar>
                      <div className="min-w-0">
                        <div className="truncate text-sm font-medium">{player.displayName}</div>
                        <div className="text-xs text-muted-foreground">座位 {player.seatNumber + 1}</div>
                      </div>
                      {!player.alive && <Badge variant="destructive">出局</Badge>}
                      {player.role && <Badge variant="outline">{player.role}</Badge>}
                    </div>
                  </div>
                ))}
                {players.length === 0 && <div className="text-sm text-muted-foreground">暂无可展示的观战数据。</div>}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base">观战聊天</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <ScrollArea className="h-[320px] rounded-md border p-2">
              <div className="space-y-2">
                {chatMessages.map((message) => (
                  <div key={message.id} className="rounded bg-slate-50 px-2 py-1 text-sm">
                    <div className="text-xs text-muted-foreground">{message.sender}</div>
                    <div>{message.content}</div>
                  </div>
                ))}
                {chatMessages.length === 0 && <div className="text-xs text-muted-foreground">暂无观战消息</div>}
              </div>
            </ScrollArea>
            <div className="flex gap-2">
              <Input value={chatInput} onChange={(event) => setChatInput(event.target.value)} placeholder="发送观战评论" />
              <Button
                size="icon"
                onClick={() => {
                  const text = chatInput.trim();
                  if (!text) return;
                  setChatMessages((prev) => [
                    ...prev,
                    { id: `${Date.now()}`, sender: "观战者", content: text, time: new Date().toISOString() },
                  ]);
                  setChatInput("");
                  toast.success("观战消息已发送");
                }}
              >
                <Send className="h-4 w-4" />
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
};

export default SpectatorRoom;
