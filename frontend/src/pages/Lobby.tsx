import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
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
import { PersonaBadge, PersonaPicker } from "./games/shared/PersonaPicker";
import { RoomSeat } from "@/types";
import { useAuth } from "@/hooks/useAuth";

const GenericLobby = () => {
  const { t } = useTranslation();
  const { roomId, gameId } = useParams();
  const { displayName, user, redirectToSsoLogin } = useAuth();

  const personaQuery = useQuery({
    queryKey: ["personas"],
    queryFn: personaApi.list,
  });

  const personas = personaQuery.data ?? [];
  const [selectedAiId, setSelectedAiId] = useState("");
  useEffect(() => {
    if (!personas.some(p => p.id === selectedAiId)) setSelectedAiId(personas[0]?.id ?? "");
  }, [personas, selectedAiId]);

  const { data: room, refetch } = useQuery({
    queryKey: ["room", roomId],
    queryFn: () => roomApi.detail(gameId || "", roomId || ""),
    enabled: !!gameId && !!roomId,
  });

  const hasJoinedRef = useRef(false);

  // --- GENERIC LOBBY FALLBACK ---
  
  const [seats, setSeats] = useState<RoomSeat[]>([]);

  const joinMutation = useMutation({
    mutationFn: (name: string) => {
      const password = room?.isPrivate ? window.prompt(t("lobby.enterPrivatePassword")) || undefined : undefined;
      return roomApi.join(gameId || "", roomId || "", name, password);
    },
    onSuccess: (data) => {
      setSeats(data.seats || []);
      refetch();
    },
    onError: () => toast.error(t("lobby.joinFailed")),
  });

  const aiMutation = useMutation({
    mutationFn: (personaId: string) => roomApi.addAi(gameId || "", roomId || "", personaId),
    onSuccess: (data) => {
      setSeats(data.seats || []);
      toast.success(t("lobby.aiSeated"));
      refetch();
    },
    onError: () => toast.error(t("lobby.addAiFailed")),
  });

  useEffect(() => {
    if (room) {
      setSeats(room.seats || []);
      if (!hasJoinedRef.current) {
        hasJoinedRef.current = true;
        if (!user) {
          void redirectToSsoLogin();
          return;
        }
        joinMutation.mutate(displayName);
      }
    }
  }, [room, displayName, user, redirectToSsoLogin]);

  const displaySeats = useMemo(() => {
    const filled = Array.from({ length: room?.maxPlayers || 9 }, (_, idx) => seats.find(s => s.seatNumber === idx) || null);
    return filled;
  }, [room, seats]);

  const isHost = !!user && (room?.hostUserId === user.id || !!seats.some(s => s.playerId === user.id && s.host));
  const full = seats.length >= (room?.maxPlayers ?? 0);

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
                <Bot className={`${compact ? 'h-2 w-2' : 'h-3 w-3'} mr-1`} /> {compact ? 'AI' : t('lobby.companion')}
              </Badge>
            )}
          </div>
          <div className={`mt-2 text-center ${compact ? 'px-1' : 'mt-4'}`}>
            <div className={`font-bold truncate ${compact ? 'text-xs max-w-[80px]' : 'max-w-[120px]'}`}>{player.displayName}</div>
            {player.ai && <PersonaBadge personaId={player.personaId} personas={personas} />}
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
              <span className="text-muted-foreground font-medium text-xs md:text-sm">{t("lobby.emptySeat")}</span>
            </Button>
          </SheetTrigger>
          <SheetContent side="bottom" className="h-[80vh]">
            <SheetHeader>
              <SheetTitle>{t("lobby.invitePlayer")}</SheetTitle>
              <SheetDescription>
                {t("lobby.inviteDesc")}
              </SheetDescription>
            </SheetHeader>
            
            <div className="mt-8 space-y-6">
              <div>
                <h4 className="text-sm font-medium mb-3">{t("lobby.aiList")}</h4>
                <ScrollArea className="h-[300px] pr-4">
                  <PersonaPicker personas={personas} selectedAiId={selectedAiId} onSelectedAiIdChange={setSelectedAiId}
                    canAddAi={isHost && !full && room?.status === "WAITING" && !aiMutation.isPending}
                    onAddAi={() => aiMutation.mutate(selectedAiId)} isHost={isHost} isWaiting={room?.status === "WAITING"}
                    full={full} isLoading={personaQuery.isPending} isError={personaQuery.isError}
                    onRetry={() => void personaQuery.refetch()} isAdding={aiMutation.isPending} addError={aiMutation.isError} />
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
            <h1 className="text-2xl md:text-3xl font-bold">{room?.name || (roomId ? t("lobby.roomNumber", { id: roomId }) : "")}</h1>
            <Badge variant="outline" className="bg-green-500/10 text-green-600 border-green-200">
              {(room?.status || "WAITING") === "PLAYING" ? t("lobby.statusPlaying") : t("lobby.statusWaiting")}
            </Badge>
          </div>
          <p className="text-sm md:text-base text-muted-foreground mt-1">
            {room ? `${room.gameId} • ${t("lobby.playerCount", { count: room.seats?.length || 0, max: room.maxPlayers })}` : t("lobby.loadingRoom")}
          </p>
        </div>
        <div className="flex gap-3">
          <Button variant="outline" size="sm" className="md:size-default" onClick={() => toast.success(t("lobby.linkCopied"))}>
            <Share2 className="mr-2 h-4 w-4" /> {t("lobby.invite")}
          </Button>
          <Button size="sm" className="md:size-lg" disabled={seats.filter(s => s).length < 6}>
            {t("lobby.startGame")}
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
          <span className="md:inline">{t("lobby.chatTitle")}</span>
        </div>
        <ScrollArea className="flex-1 p-4">
          <div className="text-sm text-slate-500">{t("lobby.chatPlaceholder")}</div>
        </ScrollArea>
        <div className="p-3 border-t flex gap-2 text-xs text-muted-foreground">
          {t("lobby.chatFooter")}
        </div>
      </Card>
    </div>
  );
};

const Lobby = () => {
  const { t } = useTranslation();
  const { gameId } = useParams();
  const GameRoomComponent = gameId ? gameRoomComponents[gameId] : undefined;

  if (GameRoomComponent) {
    return (
      <Suspense fallback={<div className="p-6 text-sm text-muted-foreground">{t("lobby.loadingRoom")}</div>}>
        <GameRoomComponent />
      </Suspense>
    );
  }

  return <GenericLobby />;
};

export default Lobby;
