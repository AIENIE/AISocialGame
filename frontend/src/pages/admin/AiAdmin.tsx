import { useEffect, useMemo, useState } from "react";
import { adminApi } from "@/services/api";
import { AdminAiDecisionTrace, AdminAiPersonaMemory } from "@/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { AlertTriangle, Bot, Brain, RefreshCcw, Search, Send, ShieldCheck, TimerReset } from "lucide-react";
import { toast } from "sonner";

const formatDate = (value?: string) => {
  if (!value) return "-";
  return new Date(value).toLocaleString();
};

const compactJson = (value?: Record<string, any>) => {
  if (!value || Object.keys(value).length === 0) return "-";
  return JSON.stringify(value);
};

const AiAdmin = () => {
  const [models, setModels] = useState<any[]>([]);
  const [userId, setUserId] = useState("1");
  const [prompt, setPrompt] = useState("请给出一句社交推理游戏开场白");
  const [result, setResult] = useState<any>(null);
  const [traces, setTraces] = useState<AdminAiDecisionTrace[]>([]);
  const [traceTotal, setTraceTotal] = useState(0);
  const [memories, setMemories] = useState<AdminAiPersonaMemory[]>([]);
  const [filters, setFilters] = useState({ gameId: "", personaId: "", qualityFlag: "" });
  const [loading, setLoading] = useState({ models: false, traces: false, memories: false, test: false });

  const traceStats = useMemo(() => {
    const fallbackCount = traces.filter((trace) => trace.fallback).length;
    const invalidCount = traces.filter((trace) => !trace.validDecision).length;
    const avgConfidence = traces
      .filter((trace) => typeof trace.confidence === "number")
      .reduce((sum, trace, _, arr) => sum + (trace.confidence || 0) / arr.length, 0);
    return { fallbackCount, invalidCount, avgConfidence };
  }, [traces]);

  const loadModels = async () => {
    setLoading((prev) => ({ ...prev, models: true }));
    try {
      setModels(await adminApi.aiModels());
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "加载模型失败");
    } finally {
      setLoading((prev) => ({ ...prev, models: false }));
    }
  };

  const loadTraces = async () => {
    setLoading((prev) => ({ ...prev, traces: true }));
    try {
      const response = await adminApi.aiDecisionTraces({
        gameId: filters.gameId || undefined,
        personaId: filters.personaId || undefined,
        qualityFlag: filters.qualityFlag || undefined,
        page: 0,
        size: 30,
      });
      setTraces(response.items || []);
      setTraceTotal(response.total || 0);
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "加载决策 trace 失败");
    } finally {
      setLoading((prev) => ({ ...prev, traces: false }));
    }
  };

  const loadMemories = async () => {
    setLoading((prev) => ({ ...prev, memories: true }));
    try {
      setMemories(await adminApi.aiPersonaMemories(filters.personaId || undefined));
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "加载 Persona 记忆失败");
    } finally {
      setLoading((prev) => ({ ...prev, memories: false }));
    }
  };

  useEffect(() => {
    loadModels();
    loadTraces();
    loadMemories();
  }, []);

  const refreshQualityData = async () => {
    await Promise.all([loadTraces(), loadMemories()]);
  };

  const runTest = async () => {
    setLoading((prev) => ({ ...prev, test: true }));
    try {
      const response = await adminApi.testChat({
        userId: Number(userId) || 1,
        messages: [{ role: "user", content: prompt }],
      });
      setResult(response);
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "AI 调用失败");
    } finally {
      setLoading((prev) => ({ ...prev, test: false }));
    }
  };

  const resetMemory = async (id: number) => {
    try {
      await adminApi.resetAiPersonaMemory(id);
      toast.success("Persona 记忆已重置");
      loadMemories();
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "重置失败");
    }
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <h2 className="text-lg font-semibold">AI 运营与质检</h2>
          <p className="text-sm text-slate-500">查看模型连通性、AI 决策 trace、质量标记和 Persona 长期记忆。</p>
        </div>
        <Button variant="outline" onClick={refreshQualityData} disabled={loading.traces || loading.memories}>
          <RefreshCcw className="mr-2 h-4 w-4" /> 刷新质检数据
        </Button>
      </div>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
        <Card className="border-slate-200">
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm text-slate-500"><Bot className="h-4 w-4" /> 可用模型</CardTitle>
          </CardHeader>
          <CardContent className="text-2xl font-semibold">{models.length}</CardContent>
        </Card>
        <Card className="border-slate-200">
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm text-slate-500"><Brain className="h-4 w-4" /> Trace 总数</CardTitle>
          </CardHeader>
          <CardContent className="text-2xl font-semibold">{traceTotal}</CardContent>
        </Card>
        <Card className="border-slate-200">
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm text-slate-500"><AlertTriangle className="h-4 w-4" /> 异常/兜底</CardTitle>
          </CardHeader>
          <CardContent className="text-2xl font-semibold">{traceStats.invalidCount}/{traceStats.fallbackCount}</CardContent>
        </Card>
        <Card className="border-slate-200">
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm text-slate-500"><ShieldCheck className="h-4 w-4" /> 平均置信度</CardTitle>
          </CardHeader>
          <CardContent className="text-2xl font-semibold">{traceStats.avgConfidence ? traceStats.avgConfidence.toFixed(2) : "-"}</CardContent>
        </Card>
      </div>

      <Tabs defaultValue="quality" className="space-y-4">
        <TabsList className="grid w-full grid-cols-3 md:w-[520px]">
          <TabsTrigger value="quality">决策 Trace</TabsTrigger>
          <TabsTrigger value="memory">Persona 记忆</TabsTrigger>
          <TabsTrigger value="gateway">网关测试</TabsTrigger>
        </TabsList>

        <TabsContent value="quality" className="space-y-4">
          <Card className="border-slate-200">
            <CardContent className="grid grid-cols-1 gap-3 pt-6 md:grid-cols-[1fr_1fr_1fr_auto]">
              <div className="space-y-2">
                <Label htmlFor="filter-game">玩法</Label>
                <Input id="filter-game" placeholder="undercover / werewolf" value={filters.gameId} onChange={(e) => setFilters((prev) => ({ ...prev, gameId: e.target.value }))} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="filter-persona">Persona</Label>
                <Input id="filter-persona" placeholder="ai1" value={filters.personaId} onChange={(e) => setFilters((prev) => ({ ...prev, personaId: e.target.value }))} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="filter-quality">质量标记</Label>
                <Input id="filter-quality" placeholder="demo_sample / REPEATED_TEMPLATE" value={filters.qualityFlag} onChange={(e) => setFilters((prev) => ({ ...prev, qualityFlag: e.target.value }))} />
              </div>
              <div className="flex items-end">
                <Button onClick={loadTraces} disabled={loading.traces} className="w-full">
                  <Search className="mr-2 h-4 w-4" /> 查询
                </Button>
              </div>
            </CardContent>
          </Card>

          <div className="space-y-3">
            {traces.map((trace) => (
              <Card key={trace.id} className="border-slate-200">
                <CardContent className="space-y-3 p-4">
                  <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge>{trace.gameId}</Badge>
                      <Badge variant="outline">{trace.phase || "-"}</Badge>
                      <Badge variant={trace.validDecision ? "secondary" : "destructive"}>{trace.action}</Badge>
                      {trace.fallback && <Badge variant="destructive">fallback</Badge>}
                    </div>
                    <div className="text-xs text-slate-500">{formatDate(trace.createdAt)}</div>
                  </div>
                  <div className="grid grid-cols-1 gap-3 text-sm md:grid-cols-4">
                    <div><span className="text-slate-500">Persona</span><div className="font-medium">{trace.personaId || "-"}</div></div>
                    <div><span className="text-slate-500">角色</span><div className="font-medium">{trace.roleKey || "-"}</div></div>
                    <div><span className="text-slate-500">耗时</span><div className="font-medium">{trace.latencyMs}ms</div></div>
                    <div><span className="text-slate-500">置信度</span><div className="font-medium">{trace.confidence ?? "-"}</div></div>
                  </div>
                  <div className="rounded-md bg-slate-50 p-3 text-sm text-slate-700">
                    <div className="font-medium text-slate-900">{trace.outputSummary || "无输出摘要"}</div>
                    <div className="mt-1">{trace.reason || "无理由摘要"}</div>
                  </div>
                  <div className="grid grid-cols-1 gap-3 text-xs text-slate-600 lg:grid-cols-3">
                    <div className="rounded border bg-white p-2"><span className="font-medium">质量</span><p className="mt-1 break-all">{compactJson(trace.quality)}</p></div>
                    <div className="rounded border bg-white p-2"><span className="font-medium">信念</span><p className="mt-1 break-all">{compactJson(trace.beliefSnapshot)}</p></div>
                    <div className="rounded border bg-white p-2"><span className="font-medium">记忆</span><p className="mt-1 break-all">{compactJson(trace.memorySnapshot)}</p></div>
                  </div>
                </CardContent>
              </Card>
            ))}
            {!traces.length && <div className="rounded-lg border border-dashed p-6 text-sm text-slate-500">暂无 trace 数据。先完成一场 AI 对局，或在本地开启 demo seed。</div>}
          </div>
        </TabsContent>

        <TabsContent value="memory" className="space-y-3">
          {memories.map((memory) => (
            <Card key={memory.id} className="border-slate-200">
              <CardContent className="space-y-3 p-4">
                <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge>{memory.personaId}</Badge>
                    <Badge variant="outline">{memory.gameId}</Badge>
                    <Badge variant="secondary">{memory.roleKey}</Badge>
                    <span className="text-xs text-slate-500">累计 {memory.gamesPlayed} 局</span>
                  </div>
                  <Button size="sm" variant="outline" onClick={() => resetMemory(memory.id)}>
                    <TimerReset className="mr-2 h-4 w-4" /> 重置
                  </Button>
                </div>
                <div className="grid grid-cols-1 gap-3 text-sm lg:grid-cols-2">
                  <div className="rounded-md bg-slate-50 p-3"><span className="font-medium">记忆摘要</span><p className="mt-1 text-slate-700">{memory.memorySummary || "-"}</p></div>
                  <div className="rounded-md bg-slate-50 p-3"><span className="font-medium">策略</span><p className="mt-1 text-slate-700">{memory.strategyNotes || "-"}</p></div>
                  <div className="rounded-md bg-slate-50 p-3"><span className="font-medium">错误记录</span><p className="mt-1 text-slate-700">{memory.mistakeNotes || "-"}</p></div>
                  <div className="rounded-md bg-slate-50 p-3"><span className="font-medium">口吻</span><p className="mt-1 text-slate-700">{memory.speechPatterns || "-"}</p></div>
                </div>
                <div className="text-xs text-slate-500">更新于 {formatDate(memory.updatedAt)}</div>
              </CardContent>
            </Card>
          ))}
          {!memories.length && <div className="rounded-lg border border-dashed p-6 text-sm text-slate-500">暂无 Persona 记忆。AI 完成行动后会自动沉淀。</div>}
        </TabsContent>

        <TabsContent value="gateway" className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_1fr]">
          <Card className="border-slate-200">
            <CardHeader>
              <CardTitle>可用模型</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 text-sm">
              {models.map((m) => (
                <div key={m.id} className="rounded border p-2">
                  <p className="font-medium">{m.displayName} ({m.provider})</p>
                  <p className="text-xs text-slate-500">input {m.inputRate} / output {m.outputRate}</p>
                </div>
              ))}
              {!models.length && <p className="text-slate-500">{loading.models ? "加载中..." : "暂无模型数据"}</p>}
            </CardContent>
          </Card>

          <Card className="border-slate-200">
            <CardHeader>
              <CardTitle>测试调用</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="space-y-2">
                <Label htmlFor="test-user-id">用户 ID</Label>
                <Input id="test-user-id" value={userId} onChange={(e) => setUserId(e.target.value)} />
              </div>
              <div className="space-y-2">
                <Label htmlFor="prompt">Prompt</Label>
                <Textarea id="prompt" value={prompt} onChange={(e) => setPrompt(e.target.value)} />
              </div>
              <Button onClick={runTest} disabled={loading.test}>
                <Send className="mr-2 h-4 w-4" /> 发送测试请求
              </Button>
              {result && (
                <div className="rounded border bg-slate-50 p-3 text-sm">
                  <p className="font-medium">模型：{result.modelKey}</p>
                  <p className="mt-2 whitespace-pre-wrap">{result.content}</p>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default AiAdmin;
