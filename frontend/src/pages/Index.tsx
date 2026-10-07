import { Card, CardContent, CardFooter, CardHeader } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ArrowRight, Gamepad2, Moon, Eye, BookOpen } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { gameApi } from "@/services/api";
import { localizeErrorMessage } from "@/i18n/errors";
import { getApiErrorMessage } from "@/services/apiError";
import { gameDescription, gameName, gameTags } from "@/i18n/gameTexts";
import { Game } from "@/types";
import { quickMatchApi } from "@/services/v2Social";
import { useAuth } from "@/hooks/useAuth";
import { toast } from "sonner";
import { DataState } from "@/components/DataState";
import { gameRoomComponents } from "./games/registry";

// Helper to render dynamic icons based on string name
const IconMap: Record<string, any> = {
  Moon: Moon,
  Spy: Eye,
  BookOpen: BookOpen,
};

const Index = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { displayName, user, loading } = useAuth();
  const { data: catalog = [], isLoading, error, refetch } = useQuery<Game[]>({
    queryKey: ["games"],
    queryFn: gameApi.list,
  });
  const games = (error ? [] : catalog).filter(game => String(game.status).toLowerCase() === "active" && gameRoomComponents[game.id]);

  const quickStart = async (gameId: string) => {
    if (!user) {
      navigate(`/game/${gameId}`);
      return;
    }
    try {
      const result = await quickMatchApi.start(gameId, displayName);
      navigate(`/room/${gameId}/${result.roomId}`);
    } catch (error: any) {
      const raw = getApiErrorMessage(error, t("index.quickMatchFailed"));
      toast.error(localizeErrorMessage(raw, "index.quickMatchFailed"));
    }
  };

  return (
    <div className="space-y-12 pb-12">
      {/* Hero Section */}
      <section className="relative overflow-hidden rounded-3xl bg-gradient-to-br from-slate-50 via-blue-50/50 to-indigo-50 border border-blue-100/50 p-12 md:p-20 text-center shadow-sm">
        <div className="relative z-10 max-w-4xl mx-auto space-y-6">
          <h1 className="text-4xl md:text-5xl font-extrabold tracking-tight text-slate-900">
            {t("index.heroTitle1")}<span className="text-blue-600">{t("index.heroTitle2")}</span>
          </h1>
          <p className="text-lg text-slate-600 max-w-2xl mx-auto leading-relaxed">
            {t("index.heroSubtitle")}
          </p>
          <div className="pt-4 flex justify-center">
            {games.length > 0 && <Button size="lg" disabled={loading} className="bg-blue-600 hover:bg-blue-700 shadow-sm px-8" onClick={() => quickStart(games[0].id)}>
              {t("common.quickStart")}
            </Button>}
          </div>
        </div>
        
        {/* Decorative background element */}
        <div className="absolute left-0 top-0 h-full w-full bg-[radial-gradient(circle_at_50%_120%,rgba(120,119,198,0.1),rgba(255,255,255,0))]" />
      </section>

      {/* Game Grid */}
      <section>
        <div className="flex items-center justify-between mb-8">
          <h2 className="text-2xl font-bold tracking-tight text-slate-900">{t("index.hotGames")}</h2>
        </div>

        {isLoading || error || games.length === 0 ? (
          <DataState loading={isLoading} error={error} empty={games.length === 0} onRetry={() => void refetch()} />
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            {games.map((game) => {
              const Icon = IconMap[game.coverUrl] || Gamepad2;
              
              const isActive = (game.status ?? '').toString().toLowerCase() === "active";
              return (
                <Card key={game.id} className="group hover:shadow-xl hover:-translate-y-1 transition-all duration-300 border-slate-200 bg-white shadow-sm overflow-hidden">
                  <CardHeader className="p-0 border-b border-slate-100">
                    <div className="h-40 w-full bg-slate-50/50 flex items-center justify-center group-hover:bg-blue-50/30 transition-colors">
                      <Icon className="h-16 w-16 text-slate-400 group-hover:text-blue-500 transition-colors" strokeWidth={1.5} />
                    </div>
                  </CardHeader>
                  <CardContent className="p-6 space-y-4">
                    <div className="space-y-2">
                      <div className="flex items-center justify-between">
                        <h3 className="font-bold text-xl text-slate-900">{gameName(game.id, game.name)}</h3>
                      </div>
                      <p className="text-sm text-slate-500 line-clamp-2 h-10 leading-relaxed">
                        {gameDescription(game.id, game.description)}
                      </p>
                    </div>
                    
                    <div className="flex flex-wrap gap-2">
                      {gameTags(game.id, game.tags).map(tag => (
                        <Badge key={tag} variant="secondary" className="text-xs font-medium bg-slate-100 text-slate-600 border border-slate-200 hover:bg-slate-200 hover:text-slate-800">
                          {tag}
                        </Badge>
                      ))}
                    </div>
                  </CardContent>
                  <CardFooter className="p-6 pt-0">
                    {isActive ? (
                      <div className="flex w-full gap-2">
                        <Button className="flex-1" onClick={() => quickStart(game.id)}>
                          {t("index.oneClickStart")}
                        </Button>
                        <Button
                          className="flex-1 gap-2 bg-slate-50 text-slate-700 border border-slate-200 hover:bg-white hover:text-blue-600 hover:border-blue-200 hover:shadow-md transition-all"
                          variant="outline"
                          onClick={() => navigate(`/game/${game.id}`)}
                        >
                          {t("index.enterLobby")} <ArrowRight className="h-4 w-4" />
                        </Button>
                      </div>
                    ) : (
                      <Button disabled className="w-full bg-slate-50 text-slate-400 border-slate-100">
                        {t("index.comingSoon")}
                      </Button>
                    )}
                  </CardFooter>
                </Card>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
};

export default Index;
