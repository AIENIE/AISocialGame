import { useParams, useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { ArrowLeft, Users, Plus, Search } from "lucide-react";
import { Input } from "@/components/ui/input";
import { useQuery } from "@tanstack/react-query";
import { gameApi, roomApi } from "@/services/api";
import { Game, Room } from "@/types";
import { quickMatchApi } from "@/services/v2Social";
import { useAuth } from "@/hooks/useAuth";
import { toast } from "sonner";

const RoomList = () => {
  const { gameId } = useParams();
  const navigate = useNavigate();
  const { displayName, user, redirectToSsoLogin } = useAuth();
  const { data: game } = useQuery<Game | undefined>({
    queryKey: ["game", gameId],
    queryFn: () => gameId ? gameApi.detail(gameId) : Promise.resolve(undefined as any),
    enabled: !!gameId,
  });

  const { data: rooms = [], isLoading } = useQuery<Room[]>({
    queryKey: ["rooms", gameId],
    queryFn: () => roomApi.list(gameId || ""),
    enabled: !!gameId,
  });

  const templateOptions = game?.configSchema.find((f) => f.id === "template")?.options || [];

  const buildTags = (room: Room) => {
    const tags: string[] = [];
    const config = (room.config || {}) as Record<string, any>;

    tags.push(room.isPrivate ? "私密房" : "公开房");

    if (room.commMode) {
      tags.push(room.commMode === "voice" ? "语音交流" : room.commMode === "text" ? "文字交流" : room.commMode);
    }

    const playerCount = config.playerCount || room.maxPlayers;
    if (playerCount) {
      tags.push(`${playerCount}人局`);
    }

    const templateLabel = config.template
      ? templateOptions.find((opt) => opt.value.toString() === config.template.toString())?.label
      : undefined;
    if (templateLabel) {
      tags.push(templateLabel);
    }

    return tags;
  };

  if (!game) return <div>游戏不存在</div>;

  const quickStart = async () => {
    if (!gameId) return;
    if (!user) {
      await redirectToSsoLogin();
      return;
    }
    try {
      const result = await quickMatchApi.start(gameId, displayName);
      navigate(`/room/${gameId}/${result.roomId}`);
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "快速匹配失败");
    }
  };

  return (
    <div className="max-w-6xl mx-auto space-y-8">
      {/* Header Section */}
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 bg-white p-6 rounded-xl border border-slate-200 shadow-sm">
        <div>
          <Button variant="ghost" className="pl-0 hover:pl-2 transition-all mb-2 text-slate-500 hover:text-slate-800" onClick={() => navigate("/")}>
            <ArrowLeft className="mr-2 h-4 w-4" /> 返回大厅
          </Button>
          <h1 className="text-3xl font-bold text-slate-900">{game.name}</h1>
          <p className="text-slate-500 mt-1">选择一个房间加入，或者创建属于你的对局</p>
        </div>
        <div className="flex gap-3 w-full md:w-auto">
          <div className="relative flex-1 md:w-64">
            <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-slate-400" />
            <Input placeholder="搜索房间号或名称..." className="pl-9 bg-slate-50 border-slate-200 focus:bg-white" />
          </div>
          <Button onClick={() => navigate(`/create/${gameId}`)} className="bg-blue-600 hover:bg-blue-700 shadow-md shadow-blue-200/50">
            <Plus className="mr-2 h-4 w-4" /> 创建房间
          </Button>
          <Button variant="outline" onClick={quickStart}>
            一键开局
          </Button>
        </div>
      </div>

      {/* Room Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {isLoading && <div className="text-slate-500">加载房间中...</div>}
        {!isLoading && rooms.map((room) => (
            <Card key={room.id} className="group hover:shadow-md transition-all duration-200 border-slate-200 bg-white">
              <CardHeader className="pb-3 border-b border-slate-50">
                <div className="flex justify-between items-start">
                  <CardTitle className="text-lg font-bold text-slate-800">{room.name}</CardTitle>
                  {room.status.toString().toLowerCase() === "playing" ? (
                    <Badge variant="secondary" className="bg-slate-100 text-slate-500 border border-slate-200">进行中</Badge>
                  ) : (
                    <Badge variant="outline" className="text-emerald-600 border-emerald-200 bg-emerald-50">等待中</Badge>
                  )}
                </div>
              </CardHeader>
              <CardContent className="py-4">
                <div className="flex items-center justify-between text-sm text-slate-500 bg-slate-50 p-3 rounded-lg border border-slate-100">
                  <div className="flex items-center gap-2">
                    <Users className="h-4 w-4 text-slate-400" />
                    <span>人数</span>
                  </div>
                  <span className="font-medium text-slate-900">
                    {room.seats?.length ?? 0} <span className="text-slate-300">/</span> {room.maxPlayers}
                  </span>
                </div>
                <div className="mt-4 flex flex-wrap gap-2">
                  {buildTags(room).map((tag) => (
                    <Badge
                      key={`${room.id}-${tag}`}
                      variant="secondary"
                      className="bg-slate-100 text-slate-600 border border-slate-200"
                    >
                      {tag}
                    </Badge>
                  ))}
                </div>
              </CardContent>
              <CardFooter className="pt-0">
                <Button 
                  className="w-full border border-slate-200" 
                  variant={room.status.toString().toLowerCase() === "playing" ? "secondary" : "default"}
                  onClick={() =>
                    room.status.toString().toLowerCase() === "playing"
                      ? navigate(`/spectate/${gameId}/${room.id}`)
                      : navigate(`/room/${gameId}/${room.id}`)
                  }
                >
                  {room.status.toString().toLowerCase() === "playing" ? "立即观战" : "立即加入"}
                </Button>
              </CardFooter>
            </Card>
          ))}
      </div>
    </div>
  );
};

export default RoomList;
