import { useState, useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ArrowLeft, Sparkles, Zap, Mic, Keyboard, Lock, Globe, ChevronDown, ChevronUp } from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { useQuery, useMutation } from "@tanstack/react-query";
import { gameApi, roomApi } from "@/services/api";
import { Game, GameConfigOption } from "@/types";
import { useAuth } from "@/hooks/useAuth";

type FormValue = string | number | boolean | undefined;
type RoomFormData = Record<string, FormValue>;

const CreateRoom = () => {
  const { gameId } = useParams();
  const navigate = useNavigate();
  const { user, redirectToSsoLogin } = useAuth();
  const { data: game } = useQuery<Game | undefined>({
    queryKey: ["game", gameId],
    queryFn: () => gameId ? gameApi.detail(gameId) : Promise.resolve(undefined),
    enabled: !!gameId,
  });
  
  const [formData, setFormData] = useState<RoomFormData>({});
  const [isAdvancedOpen, setIsAdvancedOpen] = useState(false);
  const isPlayable = !!game && (game.status ?? "").toString().toLowerCase() === "active" && game.engineBacked !== false;

  useEffect(() => {
    if (game) {
      const defaults: RoomFormData = {
        roomName: `[${game.name}] 玩家的房间`,
        isPrivate: false,
        commMode: "voice",
      };
      game.configSchema.forEach(field => {
        defaults[field.id] = field.defaultValue;
      });
      setFormData(defaults);
    }
  }, [game]);

  const handleInputChange = (id: string, value: FormValue) => {
    setFormData(prev => ({ ...prev, [id]: value }));
  };

  const createMutation = useMutation({
    mutationFn: () => roomApi.create(gameId!, {
      roomName: formData.roomName,
      isPrivate: formData.isPrivate,
      password: formData.isPrivate ? formData.password : undefined,
      commMode: formData.commMode,
      config: Object.fromEntries(
        Object.entries(formData).filter(([key]) => !["roomName", "isPrivate", "password", "commMode"].includes(key))
      ),
    }),
    onSuccess: (room) => {
      toast.success("房间创建成功");
      navigate(`/room/${game.id}/${room.id}`);
    },
    onError: () => toast.error("创建失败，请稍后重试"),
  });

  if (!game) return <div className="p-8 text-center">游戏不存在</div>;

  const handleCreate = () => {
    if (!isPlayable) {
      toast.error("该玩法暂未开放");
      return;
    }
    if (!user) {
      void redirectToSsoLogin();
      return;
    }
    if (formData.isPrivate && (!formData.password || String(formData.password).trim().length < 4)) {
      toast.error("私密房间密码至少 4 位");
      return;
    }
    createMutation.mutate();
  };

  // Helper to check if a field is "advanced" (not template or playerCount)
  const isAdvancedField = (id: string) => {
    return !["template", "playerCount"].includes(id);
  };

  return (
    <div className="max-w-6xl mx-auto pb-24 md:pb-8 px-4">
      <Button variant="ghost" className="mb-4 md:mb-6 pl-0 hover:pl-2 transition-all" onClick={() => navigate(`/game/${gameId}`)}>
        <ArrowLeft className="mr-2 h-4 w-4" /> 返回房间列表
      </Button>

      {!isPlayable && (
        <Card className="mb-6 border-slate-200 bg-slate-50">
          <CardHeader>
            <CardTitle>{game.name} 暂未开放</CardTitle>
            <CardDescription>
              该玩法已进入内容矩阵规划，当前仅展示入口和定位，正式规则与房间引擎上线后才能创建对局。
            </CardDescription>
          </CardHeader>
        </Card>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
        
        {/* --- Left Column: Basic Info (4 cols) --- */}
        <div className="lg:col-span-4 space-y-6">
          <Card className="border-slate-200 shadow-sm h-full">
            <CardHeader>
              <CardTitle>基础设置</CardTitle>
              <CardDescription>配置房间的基本信息</CardDescription>
            </CardHeader>
            <CardContent className="space-y-6">
              {/* Room Cover Preview (Mock) */}
              <div className="aspect-video rounded-lg bg-slate-100 flex items-center justify-center border-2 border-dashed border-slate-200 relative overflow-hidden group cursor-pointer">
                <div className="absolute inset-0 bg-gradient-to-br from-slate-800 to-slate-900 opacity-80" />
                <div className="relative z-10 text-center text-white">
                  <span className="text-sm font-medium">点击更换封面</span>
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="roomName">房间名称</Label>
                <Input 
                  id="roomName" 
                  value={String(formData.roomName || '')}
                  onChange={(e) => handleInputChange("roomName", e.target.value)}
                />
              </div>

              <div className="space-y-2">
                <Label>隐私设置</Label>
                <div className="grid grid-cols-2 gap-2">
                  <Button 
                    variant={!formData.isPrivate ? "default" : "outline"}
                    className={cn(!formData.isPrivate ? "bg-slate-900" : "text-slate-500")}
                    onClick={() => handleInputChange("isPrivate", false)}
                  >
                    <Globe className="mr-2 h-4 w-4" /> 公开
                  </Button>
                  <Button 
                    variant={formData.isPrivate ? "default" : "outline"}
                    className={cn(formData.isPrivate ? "bg-slate-900" : "text-slate-500")}
                    onClick={() => handleInputChange("isPrivate", true)}
                  >
                    <Lock className="mr-2 h-4 w-4" /> 私密
                  </Button>
                </div>
                {formData.isPrivate && (
                  <Input 
                    type="password" 
                    placeholder="设置房间密码" 
                    className="mt-2"
                    onChange={(e) => handleInputChange("password", e.target.value)}
                  />
                )}
              </div>

              <div className="space-y-2">
                <Label>交流模式</Label>
                <div className="grid grid-cols-2 gap-2">
                  <Button 
                    variant={formData.commMode === "voice" ? "default" : "outline"}
                    className={cn(formData.commMode === "voice" ? "bg-blue-600 hover:bg-blue-700" : "text-slate-500")}
                    onClick={() => handleInputChange("commMode", "voice")}
                  >
                    <Mic className="mr-2 h-4 w-4" /> 语音模式
                  </Button>
                  <Button 
                    variant={formData.commMode === "text" ? "default" : "outline"}
                    className={cn(formData.commMode === "text" ? "bg-blue-600 hover:bg-blue-700" : "text-slate-500")}
                    onClick={() => handleInputChange("commMode", "text")}
                  >
                    <Keyboard className="mr-2 h-4 w-4" /> 文字模式
                  </Button>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* --- Right Column: Game Config (8 cols) --- */}
        <div className="lg:col-span-8 space-y-6">
          <Card className="border-slate-200 shadow-sm">
            <CardHeader>
              <CardTitle>板子配置</CardTitle>
              <CardDescription>自定义 {game.name} 的游戏规则</CardDescription>
            </CardHeader>
            <CardContent className="space-y-8">
              
              {/* 1. Player Count */}
              {game.configSchema.find(f => f.id === "playerCount") && (
                <div className="space-y-3">
                  <Label className="text-base">玩家人数</Label>
                  <div className="flex gap-3">
                    {game.configSchema.find(f => f.id === "playerCount")?.options?.map(opt => (
                      <Button
                        key={opt.value}
                        variant={formData.playerCount === opt.value ? "default" : "outline"}
                        onClick={() => handleInputChange("playerCount", opt.value)}
                        className="flex-1"
                      >
                        {opt.label}
                      </Button>
                    ))}
                  </div>
                </div>
              )}

              {/* 2. Templates (Visual Cards) */}
              {game.configSchema.find(f => f.id === "template") && (
                <div className="space-y-3">
                  <Label className="text-base">板子预设</Label>
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    {game.configSchema.find(f => f.id === "template")?.options?.map(opt => (
                      <div 
                        key={opt.value}
                        onClick={() => handleInputChange("template", opt.value)}
                        className={cn(
                          "cursor-pointer rounded-xl border-2 p-4 transition-all hover:shadow-md",
                          formData.template === opt.value 
                            ? "border-blue-600 bg-blue-50/50" 
                            : "border-slate-200 bg-white hover:border-blue-300"
                        )}
                      >
                        <div className="font-bold text-slate-900">{opt.label}</div>
                        <div className="text-xs text-slate-500 mt-1">
                          {opt.value === "standard" && "预言家、女巫、猎人、白痴"}
                          {opt.value === "guard" && "预言家、女巫、猎人、守卫"}
                          {opt.value === "no_god" && "无神职，全员平民与狼人"}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* 3. Advanced Rules (Collapsible) */}
              <Collapsible open={isAdvancedOpen} onOpenChange={setIsAdvancedOpen} className="border rounded-lg bg-slate-50/50">
                <CollapsibleTrigger asChild>
                  <Button variant="ghost" className="w-full flex justify-between p-4 hover:bg-slate-100">
                    <span className="font-medium text-slate-700">高级规则设置</span>
                    {isAdvancedOpen ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                  </Button>
                </CollapsibleTrigger>
                <CollapsibleContent className="p-4 pt-0 space-y-4">
                  <div className="h-px bg-slate-200 mb-4" />
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                    {game.configSchema.filter(f => isAdvancedField(f.id)).map((field) => (
                      <div key={field.id} className="space-y-2">
                        <div className="flex items-center justify-between">
                          <Label htmlFor={field.id} className="text-sm text-slate-600">{field.label}</Label>
                          {field.type === "boolean" && (
                            <Switch 
                              id={field.id} 
                              checked={formData[field.id] || false}
                              onCheckedChange={(checked) => handleInputChange(field.id, checked)}
                            />
                          )}
                        </div>

                        {field.type === "select" && (
                          <Select 
                            value={formData[field.id]?.toString()} 
                            onValueChange={(val) => handleInputChange(field.id, isNaN(Number(val)) ? val : Number(val))}
                          >
                            <SelectTrigger className="bg-white h-9">
                              <SelectValue placeholder="请选择" />
                            </SelectTrigger>
                            <SelectContent>
                              {field.options?.map(opt => (
                                <SelectItem key={opt.value} value={opt.value.toString()}>
                                  {opt.label}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        )}
                        
                        {field.type === "number" && (
                           <Input 
                             type="number"
                             value={typeof formData[field.id] === "boolean" || formData[field.id] == null ? "" : formData[field.id]}
                             onChange={(e) => handleInputChange(field.id, Number(e.target.value))}
                             className="bg-white h-9"
                           />
                        )}
                      </div>
                    ))}
                  </div>
                </CollapsibleContent>
              </Collapsible>

            </CardContent>
          </Card>
        </div>
      </div>

      {/* Sticky Footer */}
      <div className="fixed bottom-0 left-0 right-0 bg-white border-t p-4 shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.05)] z-40 md:static md:bg-transparent md:border-0 md:shadow-none md:p-0 md:mt-8">
        <div className="max-w-6xl mx-auto flex items-center justify-between gap-4">
          <div className="hidden md:block">
            <div className="text-sm text-slate-500">当前配置</div>
            <div className="font-bold text-slate-900 flex gap-2 items-center">
              <span>{formData.playerCount}人局</span>
              <span className="w-1 h-1 bg-slate-300 rounded-full" />
              <span>{formData.commMode === 'voice' ? '语音' : '文字'}</span>
              <span className="w-1 h-1 bg-slate-300 rounded-full" />
              <span>{formData.template ? (formData.template === 'standard' ? '预女猎白' : formData.template) : game.name}</span>
            </div>
          </div>
          <div className="flex-1 md:flex-none flex gap-4 justify-end">
             <div className="flex items-center gap-1 md:mr-4">
                <span className="text-sm text-slate-500">消耗</span>
                <span className="text-xl font-bold text-blue-600">50</span>
                <span className="text-xs text-slate-400">金币</span>
             </div>
             <Button size="lg" className="flex-1 md:w-48 bg-blue-600 hover:bg-blue-700 shadow-lg shadow-blue-200" onClick={handleCreate} disabled={!isPlayable || createMutation.isPending}>
              <Zap className="mr-2 h-5 w-5 fill-current" />
              {isPlayable ? "创建并入座" : "暂未开放"}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default CreateRoom;
