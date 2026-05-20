import { useEffect, useMemo, useState } from "react";
import { adminApi } from "@/services/api";
import { AiSafetyControl, AiSafetyEvent, AiSafetySummary } from "@/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { AlertTriangle, Ban, CheckCircle2, PauseCircle, RefreshCcw, ShieldAlert, ShieldCheck, XCircle } from "lucide-react";
import { toast } from "sonner";

const formatDate = (value?: string) => value ? new Date(value).toLocaleString() : "-";

const SafetyAdmin = () => {
  const [summary, setSummary] = useState<AiSafetySummary | null>(null);
  const [events, setEvents] = useState<AiSafetyEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<AiSafetyEvent | null>(null);
  const [controls, setControls] = useState<AiSafetyControl[]>([]);
  const [filters, setFilters] = useState({ status: "OPEN", severity: "", source: "", roomId: "" });
  const [controlForm, setControlForm] = useState({ scope: "USER", targetKey: "", action: "BLOCK", reason: "" });
  const [loading, setLoading] = useState(false);

  const stats = useMemo(() => [
    { label: "未处理高危", value: summary?.openHighRiskEvents ?? 0, icon: ShieldAlert },
    { label: "24h 拦截/替换", value: summary?.blockedLast24h ?? 0, icon: Ban },
    { label: "成本异常", value: summary?.costAnomaliesLast24h ?? 0, icon: AlertTriangle },
    { label: "活跃控制", value: summary?.activeControls ?? 0, icon: PauseCircle },
  ], [summary]);

  const loadAll = async () => {
    setLoading(true);
    try {
      const [nextSummary, eventPage, nextControls] = await Promise.all([
        adminApi.safetySummary(),
        adminApi.safetyEvents({
          status: filters.status || undefined,
          severity: filters.severity || undefined,
          source: filters.source || undefined,
          roomId: filters.roomId || undefined,
          page: 0,
          size: 30,
        }),
        adminApi.safetyControls(),
      ]);
      setSummary(nextSummary);
      setEvents(eventPage.items || []);
      setTotal(eventPage.total || 0);
      setControls(nextControls || []);
      if (selected) {
        const fresh = eventPage.items?.find((event) => event.id === selected.id);
        setSelected(fresh || selected);
      }
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "加载安全运营数据失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAll();
  }, []);

  const ack = async (event: AiSafetyEvent) => {
    try {
      const updated = await adminApi.ackSafetyEvent(event.id);
      setSelected(updated);
      toast.success("已确认安全事件");
      loadAll();
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "确认失败");
    }
  };

  const close = async (event: AiSafetyEvent) => {
    try {
      const updated = await adminApi.closeSafetyEvent(event.id, "admin_closed");
      setSelected(updated);
      toast.success("已关闭安全事件");
      loadAll();
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "关闭失败");
    }
  };

  const createControl = async () => {
    if (!controlForm.targetKey.trim()) {
      toast.error("请输入控制目标");
      return;
    }
    try {
      await adminApi.createSafetyControl(controlForm);
      setControlForm((prev) => ({ ...prev, targetKey: "", reason: "" }));
      toast.success("临时控制已创建");
      loadAll();
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "创建控制失败");
    }
  };

  const disableControl = async (id: number) => {
    try {
      await adminApi.disableSafetyControl(id);
      toast.success("控制已停用");
      loadAll();
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "停用失败");
    }
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-col gap-3 md:flex-row md:items-end md:justify-between">
        <div>
          <h2 className="text-lg font-semibold">AI 安全与应急运营</h2>
          <p className="text-sm text-slate-500">监控风险内容、确认事件并下发用户、房间、Persona、模型或全局临时控制。</p>
        </div>
        <Button variant="outline" onClick={loadAll} disabled={loading}>
          <RefreshCcw className="mr-2 h-4 w-4" /> 刷新
        </Button>
      </div>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
        {stats.map((item) => (
          <Card key={item.label} className="border-slate-200">
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-sm text-slate-500"><item.icon className="h-4 w-4" /> {item.label}</CardTitle>
            </CardHeader>
            <CardContent className="text-2xl font-semibold">{item.value}</CardContent>
          </Card>
        ))}
      </div>

      <Card className="border-slate-200">
        <CardContent className="grid grid-cols-1 gap-3 pt-6 md:grid-cols-[1fr_1fr_1fr_1fr_auto]">
          <div className="space-y-2">
            <Label>状态</Label>
            <Select value={filters.status || "ALL"} onValueChange={(value) => setFilters((prev) => ({ ...prev, status: value === "ALL" ? "" : value }))}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部</SelectItem>
                <SelectItem value="OPEN">OPEN</SelectItem>
                <SelectItem value="ACKED">ACKED</SelectItem>
                <SelectItem value="CLOSED">CLOSED</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label>级别</Label>
            <Input value={filters.severity} onChange={(e) => setFilters((prev) => ({ ...prev, severity: e.target.value }))} placeholder="HIGH / MEDIUM" />
          </div>
          <div className="space-y-2">
            <Label>来源</Label>
            <Input value={filters.source} onChange={(e) => setFilters((prev) => ({ ...prev, source: e.target.value }))} placeholder="ROOM_CHAT / AI_PLAYER" />
          </div>
          <div className="space-y-2">
            <Label>房间</Label>
            <Input value={filters.roomId} onChange={(e) => setFilters((prev) => ({ ...prev, roomId: e.target.value }))} placeholder="room id" />
          </div>
          <div className="flex items-end">
            <Button onClick={loadAll} className="w-full">查询</Button>
          </div>
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[1.3fr_0.9fr]">
        <div className="space-y-3">
          <div className="text-sm text-slate-500">事件总数：{total}</div>
          {events.map((event) => (
            <Card key={event.id} className={`cursor-pointer border-slate-200 ${selected?.id === event.id ? "ring-2 ring-blue-500" : ""}`} onClick={() => setSelected(event)}>
              <CardContent className="space-y-3 p-4">
                <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant={event.severity === "HIGH" ? "destructive" : "secondary"}>{event.severity}</Badge>
                    <Badge variant="outline">{event.source}</Badge>
                    <Badge>{event.action}</Badge>
                    <Badge variant={event.status === "OPEN" ? "destructive" : "secondary"}>{event.status}</Badge>
                  </div>
                  <span className="text-xs text-slate-500">{formatDate(event.createdAt)}</span>
                </div>
                <div className="text-sm font-medium text-slate-900">{event.category} · {event.reason || "-"}</div>
                <div className="break-all rounded-md bg-slate-50 p-3 text-sm text-slate-700">{event.contentSummary || "无内容摘要"}</div>
              </CardContent>
            </Card>
          ))}
          {!events.length && <div className="rounded-lg border border-dashed p-6 text-sm text-slate-500">暂无安全事件。</div>}
        </div>

        <div className="space-y-4">
          <Card className="border-slate-200">
            <CardHeader><CardTitle className="text-base">事件详情</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              {!selected && <div className="text-sm text-slate-500">选择左侧事件查看详情。</div>}
              {selected && (
                <>
                  <div className="grid grid-cols-2 gap-2 text-sm">
                    <div><span className="text-slate-500">ID</span><div>{selected.id}</div></div>
                    <div><span className="text-slate-500">状态</span><div>{selected.status}</div></div>
                    <div><span className="text-slate-500">房间</span><div className="break-all">{selected.roomId || "-"}</div></div>
                    <div><span className="text-slate-500">用户</span><div className="break-all">{selected.userId || selected.playerId || "-"}</div></div>
                    <div><span className="text-slate-500">Persona</span><div>{selected.personaId || "-"}</div></div>
                    <div><span className="text-slate-500">模型</span><div>{selected.modelKey || "-"}</div></div>
                  </div>
                  <Textarea readOnly value={selected.sanitizedContent || ""} className="min-h-[90px]" />
                  <div className="flex gap-2">
                    <Button size="sm" onClick={() => ack(selected)} disabled={selected.status !== "OPEN"}>
                      <CheckCircle2 className="mr-2 h-4 w-4" /> 确认
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => close(selected)} disabled={selected.status === "CLOSED"}>
                      <XCircle className="mr-2 h-4 w-4" /> 关闭
                    </Button>
                  </div>
                </>
              )}
            </CardContent>
          </Card>

          <Card className="border-slate-200">
            <CardHeader><CardTitle className="text-base">临时控制</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              <div className="grid grid-cols-2 gap-2">
                <Select value={controlForm.scope} onValueChange={(value) => setControlForm((prev) => ({ ...prev, scope: value }))}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="USER">USER</SelectItem>
                    <SelectItem value="ROOM">ROOM</SelectItem>
                    <SelectItem value="PERSONA">PERSONA</SelectItem>
                    <SelectItem value="MODEL">MODEL</SelectItem>
                    <SelectItem value="GLOBAL">GLOBAL</SelectItem>
                  </SelectContent>
                </Select>
                <Select value={controlForm.action} onValueChange={(value) => setControlForm((prev) => ({ ...prev, action: value }))}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="BLOCK">BLOCK</SelectItem>
                    <SelectItem value="RATE_LIMIT">RATE_LIMIT</SelectItem>
                    <SelectItem value="ESCALATE">ESCALATE</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <Input value={controlForm.targetKey} onChange={(e) => setControlForm((prev) => ({ ...prev, targetKey: e.target.value }))} placeholder="用户/房间/Persona/模型，GLOBAL 用 *" />
              <Input value={controlForm.reason} onChange={(e) => setControlForm((prev) => ({ ...prev, reason: e.target.value }))} placeholder="原因" />
              <Button onClick={createControl} className="w-full"><ShieldCheck className="mr-2 h-4 w-4" /> 创建控制</Button>
              <div className="space-y-2">
                {controls.map((control) => (
                  <div key={control.id} className="flex items-center justify-between gap-2 rounded border p-2 text-sm">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{control.scope}:{control.targetKey}</div>
                      <div className="text-xs text-slate-500">{control.action} · {control.reason || "-"}</div>
                    </div>
                    <Button size="sm" variant="outline" onClick={() => disableControl(control.id)}>停用</Button>
                  </div>
                ))}
                {!controls.length && <div className="text-sm text-slate-500">暂无活跃控制。</div>}
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
};

export default SafetyAdmin;
