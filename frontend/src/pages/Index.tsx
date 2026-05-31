import { Card, CardContent, CardFooter, CardHeader } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ArrowRight, Gamepad2, Moon, Eye, BookOpen, type LucideIcon } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { gameApi, getApiErrorMessage } from "@/services/api";
import { Game } from "@/types";
import { quickMatchApi } from "@/services/v2Social";
import { useAuth } from "@/hooks/useAuth";
import { toast } from "sonner";

// Helper to render dynamic icons based on string name
const IconMap: Record<string, LucideIcon> = {
  Moon: Moon,
  Spy: Eye,
  BookOpen: BookOpen,
  MysteryCase: BookOpen,
  SecretSignal: Eye,
  MindMatch: Gamepad2,
  DebateArena: Gamepad2,
  TruthOrBluff: Eye,
};

const Index = () => {
  const navigate = useNavigate();
  const { displayName, user, redirectToSsoLogin } = useAuth();
  const { data: games = [], isLoading } = useQuery<Game[]>({
    queryKey: ["games"],
    queryFn: gameApi.list,
  });

  const quickStart = async (gameId: string) => {
    if (!user) {
      await redirectToSsoLogin();
      return;
    }
    try {
      const result = await quickMatchApi.start(gameId, displayName);
      navigate(`/room/${gameId}/${result.roomId}`);
    } catch (error: unknown) {
      toast.error(getApiErrorMessage(error, "快速匹配失败，请稍后重试"));
    }
  };

  return (
    <div className="space-y-12 pb-12">
      {/* Hero Section */}
      <section className="relative overflow-hidden rounded-3xl bg-gradient-to-br from-slate-50 via-blue-50/50 to-indigo-50 border border-blue-100/50 p-12 md:p-20 text-center shadow-sm">
        <div className="relative z-10 max-w-4xl mx-auto space-y-6">
          <h1 className="text-4xl md:text-5xl font-extrabold tracking-tight text-slate-900">
            随时随地，<span className="text-blue-600">组局开玩</span>
          </h1>
          <p className="text-lg text-slate-600 max-w-2xl mx-auto leading-relaxed">
            新一代社交推理平台。好友缺人？智能陪玩一秒补位，告别等待，即刻开局。
          </p>
          <div className="pt-4 flex justify-center">
            <Button size="lg" className="bg-blue-600 hover:bg-blue-700 shadow-sm px-8" onClick={() => quickStart("werewolf")}>
              快速开始
            </Button>
          </div>
        </div>
        
        {/* Decorative background element */}
        <div className="absolute left-0 top-0 h-full w-full bg-[radial-gradient(circle_at_50%_120%,rgba(120,119,198,0.1),rgba(255,255,255,0))]" />
      </section>

      {/* Game Grid */}
      <section>
        <div className="flex items-center justify-between mb-8">
          <h2 className="text-2xl font-bold tracking-tight text-slate-900">热门游戏</h2>
          <Button variant="link" className="text-slate-500 hover:text-blue-600">查看全部</Button>
        </div>

        {isLoading ? (
          <div className="text-slate-500">正在获取游戏列表...</div>
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
                        <h3 className="font-bold text-xl text-slate-900">{game.name}</h3>
                        {isActive && (
                          <div className="flex items-center text-xs text-emerald-600 font-medium bg-emerald-50 px-2.5 py-1 rounded-full border border-emerald-100">
                            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 mr-1.5 animate-pulse" />
                            {game.onlineCount} 在线
                          </div>
                        )}
                      </div>
                      <p className="text-sm text-slate-500 line-clamp-2 h-10 leading-relaxed">
                        {game.description}
                      </p>
                    </div>
                    
                    <div className="flex flex-wrap gap-2">
                      {game.tags.map(tag => (
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
                          一键开局
                        </Button>
                        <Button
                          className="flex-1 gap-2 bg-slate-50 text-slate-700 border border-slate-200 hover:bg-white hover:text-blue-600 hover:border-blue-200 hover:shadow-md transition-all"
                          variant="outline"
                          onClick={() => navigate(`/game/${game.id}`)}
                        >
                          进入大厅 <ArrowRight className="h-4 w-4" />
                        </Button>
                      </div>
                    ) : (
                      <Button disabled className="w-full bg-slate-50 text-slate-400 border-slate-100">
                        敬请期待
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
