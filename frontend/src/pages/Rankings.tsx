import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Trophy, Medal, Crown } from "lucide-react";
import { useTranslation } from "react-i18next";
import { rankingApi } from "@/services/api";
import { useQuery } from "@tanstack/react-query";
import { PlayerStats } from "@/types";
import { DataState } from "@/components/DataState";
import { gameName } from "@/i18n/gameTexts";

const RankItem = ({ item, rank }: { item: PlayerStats; rank: number }) => {
  const { t } = useTranslation();
  const isTop3 = rank <= 3;
  
  return (
    <div className={`flex items-center p-3 md:p-4 rounded-xl transition-colors ${isTop3 ? 'bg-gradient-to-r from-yellow-50/50 to-transparent border border-yellow-100' : 'hover:bg-slate-50 border border-transparent'}`}>
      <div className="w-8 md:w-12 flex justify-center font-bold text-base md:text-lg">
        {rank === 1 && <Crown className="h-5 w-5 md:h-6 md:w-6 text-yellow-500 fill-yellow-500" />}
        {rank === 2 && <Medal className="h-5 w-5 md:h-6 md:w-6 text-slate-400 fill-slate-400" />}
        {rank === 3 && <Medal className="h-5 w-5 md:h-6 md:w-6 text-amber-700 fill-amber-700" />}
        {rank > 3 && <span className="text-slate-500">{rank}</span>}
      </div>
      
      <div className="flex items-center gap-3 md:gap-4 flex-1 ml-2 md:ml-4">
        <Avatar className={`h-10 w-10 md:h-12 md:w-12 border-2 ${isTop3 ? 'border-yellow-200' : 'border-transparent'}`}>
          <AvatarImage src={item.avatar} />
          <AvatarFallback>{item.displayName[0]}</AvatarFallback>
        </Avatar>
        <div>
          <div className="font-bold text-slate-900 text-sm md:text-base">{item.displayName}</div>
          <div className="text-[10px] md:text-xs text-slate-500">ID: {item.playerId.substring(0, 8)}</div>
        </div>
      </div>

      <div className="flex gap-4 md:gap-8 text-right mr-2 md:mr-4">
        <div className="hidden md:block">
          <div className="text-xs text-slate-400 uppercase">{t("rankings.wins")}</div>
          <div className="font-medium text-slate-700">{item.wins}/{item.gamesPlayed}</div>
        </div>
        <div className="w-12 md:w-20">
          <div className="text-[10px] md:text-xs text-slate-400 uppercase">{t("rankings.score")}</div>
          <div className="font-bold text-blue-600 text-sm md:text-base">{item.score}</div>
        </div>
      </div>
    </div>
  );
};

const RankingList = ({ gameId }: { gameId: string }) => {
  const { t } = useTranslation();
  const { data = [], isLoading, error, refetch } = useQuery<PlayerStats[]>({
    queryKey: ["rankings", gameId],
    queryFn: () => rankingApi.list(gameId),
  });

  const gameLabel = gameId === "total" ? t("rankings.global") : gameName(gameId, gameId);

  return (
    <Card className="border-slate-200 shadow-sm">
      <CardHeader className="bg-slate-50/50 border-b border-slate-100 pb-4">
        <CardTitle className="flex items-center gap-2 text-base md:text-lg">
          <Trophy className="h-4 w-4 md:h-5 md:w-5 text-yellow-500" /> {t("rankings.rankTitle", { game: gameLabel })}
        </CardTitle>
      </CardHeader>
      <CardContent className="p-2 md:p-4 space-y-1 md:space-y-2">
        {(isLoading || error || !data.length) && <DataState loading={isLoading} error={error} empty={!data.length} onRetry={() => void refetch()} />}
        {!error && data.map((item, idx) => (
          <RankItem key={item.id} item={item} rank={idx + 1} />
        ))}
      </CardContent>
    </Card>
  );
};

const Rankings = () => {
  const { t } = useTranslation();
  return (
    <div className="max-w-4xl mx-auto space-y-6 md:space-y-8">
      <div className="text-center space-y-2">
        <h1 className="text-2xl md:text-3xl font-bold text-slate-900">{t("rankings.title")}</h1>
        <p className="text-sm md:text-base text-slate-500">{t("rankings.subtitle")}</p>
      </div>

      <Tabs defaultValue="werewolf" className="w-full">
        <div className="flex justify-center mb-6 md:mb-8">
          <TabsList className="grid w-full max-w-md grid-cols-3">
            <TabsTrigger value="werewolf">{gameName("werewolf")}</TabsTrigger>
            <TabsTrigger value="undercover">{gameName("undercover")}</TabsTrigger>
            <TabsTrigger value="total">{t("rankings.total")}</TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="werewolf">
          <RankingList gameId="werewolf" />
        </TabsContent>
        <TabsContent value="undercover">
          <RankingList gameId="undercover" />
        </TabsContent>
        <TabsContent value="total">
          <RankingList gameId="total" />
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default Rankings;
