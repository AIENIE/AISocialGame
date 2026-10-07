import { DataState } from "@/components/DataState";
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
import { ArrowLeft, Sparkles, Zap, Keyboard, Lock, Globe, ChevronDown, ChevronUp } from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { useQuery, useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { gameApi, roomApi } from "@/services/api";
import { localizeErrorMessage } from "@/i18n/errors";
import { gameFieldLabel, gameName, gameOptionLabel } from "@/i18n/gameTexts";
import { getApiErrorMessage } from "@/services/apiError";
import { Game, GameConfigOption } from "@/types";
import { useAuth } from "@/hooks/useAuth";
import { WEREWOLF_ROLE_LABELS, werewolfBoardRoles } from "./games/shared/werewolfSetup";

const CreateRoom = () => {
  const { t, i18n } = useTranslation();
  const english = i18n.language.startsWith("en");
  const { gameId } = useParams();
  const navigate = useNavigate();
  const { user, redirectToSsoLogin } = useAuth();
  const { data: game, isPending: gamePending, error: gameError, refetch: reloadGame } = useQuery<Game | undefined>({
    queryKey: ["game", gameId],
    queryFn: () => gameId ? gameApi.detail(gameId) : Promise.resolve(undefined as any),
    enabled: !!gameId,
    retry: false,
  });
  
  const [formData, setFormData] = useState<Record<string, any>>({});
  const [isAdvancedOpen, setIsAdvancedOpen] = useState(false);
  const [customWords, setCustomWords] = useState([{ wordA: "", wordB: "" }]);
  const roleComposition = (template: string) => Object.entries(werewolfBoardRoles(template, Number(formData.playerCount || 12)))
    .map(([role, count]) => `${count} ${t(`game.werewolf.role.${role}`, { defaultValue: WEREWOLF_ROLE_LABELS[role] || role })}`)
    .join(" · ");

  useEffect(() => {
    if (game) {
      const defaults: Record<string, any> = {
        roomName: t("create.defaultRoomName", { game: gameName(game.id, game.name) }),
        isPrivate: false,
        commMode: "text",
      };
      game.configSchema.forEach(field => {
        defaults[field.id] = field.defaultValue;
      });
      setFormData(defaults);
    }
  }, [game, t]);

  const handleInputChange = (id: string, value: any) => {
    setFormData(prev => ({ ...prev, [id]: value }));
  };

  const createMutation = useMutation({
    mutationFn: () => roomApi.create(gameId!, {
      roomName: formData.roomName,
      isPrivate: formData.isPrivate,
      password: formData.isPrivate ? formData.password : undefined,
      commMode: formData.commMode,
      config: { ...Object.fromEntries(
        Object.entries(formData).filter(([key]) => !["roomName", "isPrivate", "password", "commMode"].includes(key))
      ), ...(gameId === "undercover" && formData.wordPack === "custom" ? { customWords } : {}) },
    }),
    onSuccess: (room) => {
      toast.success(t("create.success"));
      navigate(`/room/${gameId}/${room.id}`);
    },
    onError: (error: unknown) => {
      const raw = getApiErrorMessage(error, t("create.failed"));
      toast.error(localizeErrorMessage(raw, "create.failed"));
    },
  });

  const handleCreate = () => {
    if (!user) {
      void redirectToSsoLogin();
      return;
    }
    if (formData.isPrivate && (!formData.password || String(formData.password).trim().length < 4)) {
      toast.error(t("create.passwordTooShort"));
      return;
    }
    if (gameId === "undercover" && formData.spyMode === "manual") {
      const maximum = Math.floor((Number(formData.playerCount) - 1) / 3);
      if (!Number.isInteger(formData.spyCount) || formData.spyCount < 1 || formData.spyCount > maximum) {
        toast.error(english ? `Choose between 1 and ${maximum} undercover players.` : `卧底数量需为1-${maximum}的整数`); return;
      }
    }
    if (gameId === "undercover" && formData.wordPack === "custom") {
      const seen = new Set<string>();
      for (let index = 0; index < customWords.length; index++) {
        const pair = customWords[index];
        const a = pair.wordA.normalize("NFKC").trim(), b = pair.wordB.normalize("NFKC").trim();
        const key = [a.toLocaleLowerCase(), b.toLocaleLowerCase()].sort().join("\u0000");
        if ([...a].length < 2 || [...a].length > 20 || [...b].length < 2 || [...b].length > 20 || a.toLocaleLowerCase() === b.toLocaleLowerCase() || seen.has(key)) {
          toast.error(english ? `Check pair ${index + 1}: use two different words of 2–20 characters, with no duplicate pairs.` : `请检查第${index + 1}组：每词2-20字，两个词不同且词对不重复`); return;
        }
        seen.add(key);
      }
    }
    createMutation.mutate();
  };

  // Helper to check if a field is "advanced" (not template or playerCount)
  const isAdvancedField = (id: string) => {
    return !["template", "playerCount"].includes(id);
  };

  if (gamePending || gameError || !game) return <DataState loading={gamePending} error={gameError} onRetry={() => void reloadGame()} />;

  return (
    <div className="max-w-6xl mx-auto pb-24 md:pb-8 px-4">
      <Button variant="ghost" className="mb-4 md:mb-6 pl-0 hover:pl-2 transition-all" onClick={() => navigate(`/game/${gameId}`)}>
        <ArrowLeft className="mr-2 h-4 w-4" /> {t("create.back")}
      </Button>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
        
        {/* --- Left Column: Basic Info (4 cols) --- */}
        <div className="lg:col-span-4 space-y-6">
          <Card className="border-slate-200 shadow-sm h-full">
            <CardHeader>
              <CardTitle>{t("create.basicTitle")}</CardTitle>
              <CardDescription>{t("create.basicDesc")}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-6">
              <div className="space-y-2">
                <Label htmlFor="roomName">{t("create.roomName")}</Label>
                <Input 
                  id="roomName" 
                  value={formData.roomName || ''}
                  onChange={(e) => handleInputChange("roomName", e.target.value)}
                />
              </div>

              <div className="space-y-2">
                <Label>{t("create.privacy")}</Label>
                <div className="grid grid-cols-2 gap-2">
                  <Button 
                    variant={!formData.isPrivate ? "default" : "outline"}
                    className={cn(!formData.isPrivate ? "bg-slate-900" : "text-slate-500")}
                    onClick={() => handleInputChange("isPrivate", false)}
                  >
                    <Globe className="mr-2 h-4 w-4" /> {t("create.public")}
                  </Button>
                  <Button 
                    variant={formData.isPrivate ? "default" : "outline"}
                    className={cn(formData.isPrivate ? "bg-slate-900" : "text-slate-500")}
                    onClick={() => handleInputChange("isPrivate", true)}
                  >
                    <Lock className="mr-2 h-4 w-4" /> {t("create.private")}
                  </Button>
                </div>
                {formData.isPrivate && (
                  <Input 
                    type="password" 
                    placeholder={t("create.passwordPlaceholder")}
                    className="mt-2"
                    onChange={(e) => handleInputChange("password", e.target.value)}
                  />
                )}
              </div>

              <div className="space-y-2">
                <Label>{t("create.commMode")}</Label>
                <div className="flex items-center rounded-md border px-3 py-2 text-sm text-slate-600">
                  <Keyboard className="mr-2 h-4 w-4" /> {t("create.text")}
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* --- Right Column: Game Config (8 cols) --- */}
        <div className="lg:col-span-8 space-y-6">
          <Card className="border-slate-200 shadow-sm">
            <CardHeader>
              <CardTitle>{t("create.configTitle")}</CardTitle>
              <CardDescription>{t("create.configDesc", { game: gameName(game.id, game.name) })}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-8">
              
              {/* 1. Player Count */}
              {game.configSchema.find(f => f.id === "playerCount") && (
                <div className="space-y-3">
                  <Label className="text-base">{t("create.playerCount")}</Label>
                  <div className="flex gap-3">
                    {game.configSchema.find(f => f.id === "playerCount")?.options?.map(opt => (
                      <Button
                        key={opt.value}
                        variant={formData.playerCount === opt.value ? "default" : "outline"}
                        onClick={() => handleInputChange("playerCount", opt.value)}
                        className="flex-1"
                      >
                        {gameOptionLabel(gameId, "playerCount", opt.value, opt.label)}
                      </Button>
                    ))}
                  </div>
                </div>
              )}

              {/* 2. Templates (Visual Cards) */}
              {game.configSchema.find(f => f.id === "template") && (
                <div className="space-y-3">
                  <Label className="text-base">{t("create.template")}</Label>
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
                        <div className="font-bold text-slate-900">{gameOptionLabel(gameId, "template", opt.value, opt.label)}</div>
                        <div className="text-xs text-slate-500 mt-1">
                          {gameId === "werewolf" ? roleComposition(String(opt.value)) : t(`create.templateDesc.${opt.value}`, { defaultValue: opt.label })}
                        </div>
                      </div>
                    ))}
                  </div>
                  {gameId === "werewolf" && (
                    <p className="text-sm leading-6 text-slate-600" data-testid="create-werewolf-setup-note">
                      {t("game.werewolf.rulesPreviewNote")}
                    </p>
                  )}
                </div>
              )}

              {/* 3. Advanced Rules (Collapsible) */}
              <Collapsible open={isAdvancedOpen} onOpenChange={setIsAdvancedOpen} className="border rounded-lg bg-slate-50/50">
                <CollapsibleTrigger asChild>
                  <Button variant="ghost" className="w-full flex justify-between p-4 hover:bg-slate-100">
                    <span className="font-medium text-slate-700">{t("create.advanced")}</span>
                    {isAdvancedOpen ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                  </Button>
                </CollapsibleTrigger>
                <CollapsibleContent className="p-4 pt-0 space-y-4">
                  <div className="h-px bg-slate-200 mb-4" />
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                    {game.configSchema.filter(f => isAdvancedField(f.id) && (f.id !== "spyCount" || formData.spyMode === "manual")).map((field) => (
                      <div key={field.id} className="space-y-2">
                        <div className="flex items-center justify-between">
                          <Label htmlFor={field.id} className="text-sm text-slate-600">{gameFieldLabel(gameId, field.id, field.label)}</Label>
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
                              <SelectValue placeholder={t("create.selectPlaceholder")} />
                            </SelectTrigger>
                            <SelectContent>
                              {field.options?.map(opt => (
                                <SelectItem key={opt.value} value={opt.value.toString()}>
                                  {gameOptionLabel(gameId, field.id, opt.value, opt.label)}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        )}
                        
                        {field.type === "number" && (
                           <Input 
                             id={field.id}
                             type="number"
                             min={field.min}
                             max={field.id === "spyCount" ? Math.floor((Number(formData.playerCount) - 1) / 3) : field.max}
                             value={formData[field.id]}
                             onChange={(e) => handleInputChange(field.id, Number(e.target.value))}
                             className="bg-white h-9"
                           />
                        )}
                      </div>
                    ))}
                  </div>
                  {gameId === "undercover" && formData.wordPack === "custom" && (
                    <div className="space-y-3 border-t border-slate-200 pt-4">
                      <div>
                        <h3 className="font-medium text-slate-900">{english ? "Your word pairs" : "自定义词对"}</h3>
                        <p className="mt-1 text-sm leading-6 text-slate-600">{english ? "You will host and watch this game without taking a player seat. Your word pairs remain hidden from the players. Add 1–100 pairs." : "你将担任出题主持，组织和观战，不占玩家席位。词对不会发送给参赛者。可添加1-100组。"}</p>
                      </div>
                      <div className="space-y-2">
                        {customWords.map((pair, index) => (
                          <div className="grid grid-cols-[1.5rem_1fr_1fr_auto] items-center gap-2" key={index}>
                            <span className="text-xs text-slate-500">{index + 1}</span>
                            <Input aria-label={english ? `Pair ${index + 1} word A` : `第${index + 1}组词A`} maxLength={20} value={pair.wordA} placeholder={english ? "Word A" : "词语A"} onChange={e => setCustomWords(rows => rows.map((row, i) => i === index ? { ...row, wordA: e.target.value } : row))} />
                            <Input aria-label={english ? `Pair ${index + 1} word B` : `第${index + 1}组词B`} maxLength={20} value={pair.wordB} placeholder={english ? "Word B" : "词语B"} onChange={e => setCustomWords(rows => rows.map((row, i) => i === index ? { ...row, wordB: e.target.value } : row))} />
                            <Button variant="ghost" disabled={customWords.length === 1} onClick={() => setCustomWords(rows => rows.filter((_, i) => i !== index))}>{english ? "Remove" : "移除"}</Button>
                          </div>
                        ))}
                      </div>
                      <Button variant="outline" disabled={customWords.length >= 100} onClick={() => setCustomWords(rows => [...rows, { wordA: "", wordB: "" }])}>{english ? "Add pair" : "添加一组"}</Button>
                    </div>
                  )}
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
            <div className="text-sm text-slate-500">{t("create.currentConfig")}</div>
            <div className="font-bold text-slate-900 flex gap-2 items-center">
              <span>{t("create.playersGame", { count: formData.playerCount })}</span>
              <span className="w-1 h-1 bg-slate-300 rounded-full" />
              <span>{t('create.textShort')}</span>
              <span className="w-1 h-1 bg-slate-300 rounded-full" />
              <span>{formData.template ? gameOptionLabel(gameId, "template", formData.template, formData.template) : gameName(game.id, game.name)}</span>
            </div>
          </div>
          <div className="flex-1 md:flex-none flex gap-4 justify-end">
             <Button size="lg" disabled={createMutation.isPending} className="flex-1 md:w-48 bg-blue-600 hover:bg-blue-700 shadow-lg shadow-blue-200" onClick={handleCreate}>
              <Zap className="mr-2 h-5 w-5 fill-current" />
              {gameId === "undercover" && formData.wordPack === "custom" ? (english ? "Create and host" : "创建并主持") : t("create.createAndSeat")}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default CreateRoom;
