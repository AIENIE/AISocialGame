import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from "@/components/ui/sheet";
import { ScrollArea, ScrollBar } from "@/components/ui/scroll-area";
import { Bot, Plus, Share2, MessageSquare, Mic, Send } from "lucide-react";
import { toast } from "sonner";
import { Input } from "@/components/ui/input";
import { gameRoomComponents } from "./games/registry";
import { useQuery, useMutation } from "@tanstack/react-query";
import { personaApi, roomApi } from "@/services/api";
import { RoomSeat } from "@/types";
import { useAuth } from "@/hooks/useAuth";
import { buildPlayerStorageUserKey, getRoomPlayerId, setRoomPlayerId } from "@/utils/playerStorage";

const Lobby = () => {
  const { roomId, gameId } = useParams();
  const { displayName, user } = useAuth();
  const storageUserKey = buildPlayerStorageUserKey(user?.id, displayName);

  const { data: personas = [] } = useQuery({
    queryKey: ["personas"],
    queryFn: personaApi.list,
  });

  const { data: room, refetch } = useQuery({
    queryKey: ["room", roomId],
    queryFn: () => roomApi.detail(gameId || "", roomId || ""),
    enabled: !!gameId && !!roomId,
  });

  const hasJoinedRef = useRef(false);

  const GameRoomComponent = gameId ? gameRoomComponents[gameId] : undefined;
  if (GameRoomComponent) {
    return (
      <Suspense fallback={<div className="p-6 text-sm text-muted-foreground">正在加载房间...</div>}>
        <GameRoomComponent />
      </Suspense>
    );
  }

  // --- GENERIC LOBBY FALLBACK ---
  
  const [seats, setSeats] = useState<RoomSeat[]>([]);

  const joinMutation = useMutation({
    mutationFn: (name: string) => roomApi.join(gameId || "", roomId || "", name, roomId ? getRoomPlayerId(roomId, storageUserKey) || undefined : undefined),
    onSuccess: (data) => {
      setSeats(data.seats || []);
      if ((data as any).selfPlayerId && roomId) {
        setRoomPlayerId(roomId, storageUserKey, (data as any).selfPlayerId);
      }
      refetch();
    },
    onError: () => toast.error("加入房间失败"),
  });

  const aiMutation = useMutation({
    mutationFn: (personaId: string) => roomApi.addAi(gameId || "", roomId || "", personaId),
    onSuccess: (data) => {
      setSeats(data.seats || []);
      toast.success("AI 已入座");
      refetch();
    },
    onError: () => toast.error("添加 AI 失败"),
  });

  useEffect(() => {
    if (room) {
      setSeats(room.seats || []);
      if (!hasJoinedRef.current) {
        hasJoinedRef.current = true;
        joinMutation.mutate(displayName);
      }
    }
  }, [room, displayName]);

  const displaySeats = useMemo(() => {
    const filled = Array.from({ length: room?.maxPlayers || 9 }, (_, idx) => seats.find(s => s.seatNumber === idx) || null);
    return filled;
  }, [room, seats]);

  const addAi = (personaId: string) => {
    aiMutation.mutate(personaId);
  };

  const PlayerCard = ({ player, index, compact = false }: { player: (RoomSeat & { trait?: string }) | null, index: number, compact?: boolean }) => (
    <Card className={`relative flex flex-col items-center justify-center border-2 border-dashed transition-all
      ${compact ? 'w-24 h-32 min-w-[6rem]' : 'h-48'} 
      ${player ? 'border-solid border-primary/20 bg-card' : 'border-muted-foreground/20 bg-muted/10'}`}
    >
      {player ? (
        <>
          <div className="relative">
            <Avatar className={`${compact ? 'h-12 w-12' : 'h-20 w-20'} border-2 border-background shadow-sm`}>
              <AvatarImage src={player.avatar} />
              <AvatarFallback>{player.displayName?.[0] || "?"}</AvatarFallback>
            </Avatar>
            {player.ai && (
              <Badge className={`absolute -bottom-2 -right-2 bg-blue-600 hover:bg-blue-700 ${compact ? 'px-1 py-0 text-[10px]' : ''}`}>
                <Bot className={`${compact ? 'h-2 w-2' : 'h-3 w-3'} mr-1`} /> {compact ? 'AI' : '陪玩'}
              </Badge>
            )}
          </div>
          <div className={`mt-2 text-center ${compact ? 'px-1' : 'mt-4'}`}>
            <div className={`font-bold truncate ${compact ? 'text-xs max-w-[80px]' : 'max-w-[120px]'}`}>{player.displayName}</div>
            {player.ai && !compact && <div className="text-xs text-muted-foreground">{player.trait || "智能陪玩"}</div>}
          </div>
          {player.ready && (
            <div className="absolute top-2 right-2 h-2 w-2 md:h-3 md:w-3 bg-green-500 rounded-full ring-2 ring-background" />
          )}
        </>
      ) : (
        <Sheet>
          <SheetTrigger asChild>
            <Button variant="ghost" className="h-full w-full flex flex-col gap-2 hover:bg-transparent p-0">
              <div className={`${compact ? 'h-8 w-8' : 'h-12 w-12'} rounded-full bg-muted flex items-center justify-center`}>
                <Plus className={`${compact ? 'h-4 w-4' : 'h-6 w-6'} text-muted-foreground`} />
              </div>
              <span className="text-muted-foreground font-medium text-xs md:text-sm">空位</span>
            </Button>
          </SheetTrigger>
          <SheetContent side="bottom" className="h-[80vh]">
            <SheetHeader>
              <SheetTitle>邀请玩家</SheetTitle>
              <SheetDescription>
                邀请好友加入，或添加智能陪玩补位。
              </SheetDescription>
            </SheetHeader>
            
            <div className="mt-8 space-y-6">
              <div>
                <h4 className="text-sm font-medium mb-3">智能陪玩列表</h4>
                <ScrollArea className="h-[300px] pr-4">
                  <div className="space-y-3">
                    {personas.map(persona => (
                      <div key={persona.id} className="flex items-center justify-between p-3 rounded-lg border bg-card hover:bg-accent transition-colors">
                        <div className="flex items-center gap-3">
                          <Avatar>
                            <AvatarImage src={persona.avatar} />
                          </Avatar>
                        </div>
                        <div className="flex-1 px-3">
                          <div className="font-medium">{persona.name}</div>
                          <div className="text-xs text-muted-foreground">{persona.trait}</div>
                        </div>
                        <Button size="sm" variant="secondary" onClick={() => addAi(persona.id)}>
                          添加
                        </Button>
                      </div>
                    ))}
                  </div>
                </ScrollArea>
              </div>
            </div>
          </SheetContent>
        </Sheet>
      )}
    </Card>
  );

  return (
    <div className="max-w-5xl mx-auto flex flex-col h-[calc(100vh-8rem)] md:h-auto md:block">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-6">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl md:text-3xl font-bold">{room?.name || `房间 #${roomId}`}</h1>
            <Badge variant="outline" className="bg-green-500/10 text-green-600 border-green-200">
              {(room?.status || "WAITING") === "PLAYING" ? "进行中" : "等待中"}
            </Badge>
          </div>
          <p className="text-sm md:text-base text-muted-foreground mt-1">
            {room ? `${room.gameId} • ${room.seats?.length || 0}/${room.maxPlayers} 人` : "准备进入房间..."}
          </p>
        </div>
        <div className="flex gap-3">
          <Button variant="outline" size="sm" className="md:size-default" onClick={() => toast.success("链接已复制！")}>
            <Share2 className="mr-2 h-4 w-4" /> 邀请
          </Button>
          <Button size="sm" className="md:size-lg" disabled={seats.filter(s => s).length < 6}>
            开始游戏
          </Button>
        </div>
      </div>

      {/* Mobile View: Horizontal Scroll (Scheme 2B) */}
      <div className="md:hidden mb-4">
        <ScrollArea className="w-full whitespace-nowrap rounded-md border bg-slate-50/50 p-4">
          <div className="flex w-max space-x-4">
            {displaySeats.map((player, index) => {
              const enriched = player ? { ...player, trait: personas.find(p => p.id === player.personaId)?.trait } : null;
              return (
                <div key={index} className="shrink-0">
                  <PlayerCard player={enriched} index={index} compact={true} />
                </div>
              );
            })}
          </div>
          <ScrollBar orientation="horizontal" />
        </ScrollArea>
      </div>

      {/* Desktop View: Grid */}
      <div className="hidden md:grid grid-cols-3 lg:grid-cols-5 gap-4 mb-8">
        {displaySeats.map((player, index) => {
          const enriched = player ? { ...player, trait: personas.find(p => p.id === player.personaId)?.trait } : null;
          return <PlayerCard key={index} player={enriched} index={index} />;
        })}
      </div>

      {/* Chat Area (Expanded on Mobile) */}
      <Card className="flex-1 flex flex-col min-h-0 md:h-64">
        <div className="p-3 border-b text-sm font-medium flex items-center gap-2 bg-slate-50/50">
          <MessageSquare className="h-4 w-4" /> 
          <span className="md:inline">大厅聊天</span>
        </div>
        <ScrollArea className="flex-1 p-4">
          <div className="text-sm text-slate-500">暂未接入房间聊天，开始游戏后请在玩法页面查看实时日志。</div>
        </ScrollArea>
        <div className="p-3 border-t flex gap-2 text-xs text-muted-foreground">
          房间聊天功能将随游戏内对局日志统一展示。
        </div>
      </Card>
    </div>
  );
};

export default Lobby;
