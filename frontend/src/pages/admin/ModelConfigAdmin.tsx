import { useEffect, useState } from "react";
import { RefreshCcw, Save } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { AdminDataState } from "@/components/admin/AdminDataState";
import { adminApi, getApiErrorMessage } from "@/services/api";
import type { AiFunctionCode, AiModelConfiguration, CatalogModel, FunctionModelConfig, SaveFunctionModelConfig, ThinkingEffort, ThinkingMode } from "@/types/aiModelConfig";

const selectClass = "h-10 w-full rounded-md border border-slate-300 bg-white px-3 text-sm text-slate-900 disabled:cursor-not-allowed disabled:opacity-50";
const modeLabels: Record<ThinkingMode, string> = { DEFAULT: "跟随服务默认", ENABLED: "开启思考", DISABLED: "关闭思考" };
type Drafts = Partial<Record<AiFunctionCode, SaveFunctionModelConfig>>;
function draftOf(row: FunctionModelConfig, catalogVersion: number): SaveFunctionModelConfig {
  return { modelKey: row.modelKey, thinkingMode: row.thinkingMode, thinkingEffort: row.thinkingEffort, version: row.version, catalogVersion };
}
function sameSettings(a: SaveFunctionModelConfig, b: FunctionModelConfig) {
  return a.modelKey === b.modelKey && a.thinkingMode === b.thinkingMode && a.thinkingEffort === b.thinkingEffort;
}
function allowedModes(model?: CatalogModel): ThinkingMode[] {
  const thinking = model?.thinking;
  return ["DEFAULT", ...(thinking?.declared && thinking.supportsEnable ? ["ENABLED"] : []), ...(thinking?.declared && thinking.supportsDisable ? ["DISABLED"] : [])] as ThinkingMode[];
}
function defaultThinking(model: CatalogModel) {
  const thinking = model.thinking;
  const enabled = thinking?.defaultEnabled;
  const effort = thinking?.defaultEffort;
  return `${enabled == null ? "开关未配置" : enabled ? "默认开启" : "默认关闭"} · ${effort == null ? "强度未配置" : effort === "UNSPECIFIED" ? "无强度偏好" : thinking?.options.find(option => option.effort === effort)?.label || effort}`;
}

export default function ModelConfigAdmin() {
  const [data, setData] = useState<AiModelConfiguration>();
  const [drafts, setDrafts] = useState<Drafts>({});
  const [readError, setReadError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState("");
  const [saving, setSaving] = useState<AiFunctionCode | null>(null);
  const [saveErrors, setSaveErrors] = useState<Partial<Record<AiFunctionCode, string>>>({});

  const load = async () => {
    setLoading(true); setReadError(undefined);
    try {
      const next = await adminApi.modelConfiguration();
      setData(next); setDrafts(Object.fromEntries(next.functions.map(row => [row.functionCode, draftOf(row, next.catalogVersion)])));
      setSaveErrors({});
    } catch (error) { setReadError(error); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, []);

  const sync = async () => {
    setSyncing(true); setSyncError("");
    try {
      const next = await adminApi.syncModelConfiguration();
      setDrafts(previous => Object.fromEntries(next.functions.map(row => {
        const draft = previous[row.functionCode];
        const old = data?.functions.find(item => item.functionCode === row.functionCode);
        return [row.functionCode, draft && old && !sameSettings(draft, old)
          ? { ...draft, catalogVersion: next.catalogVersion } : draftOf(row, next.catalogVersion)];
      })));
      setData(next); toast.success(`已同步 ${next.models.length} 个模型`);
    } catch (error) { setSyncError(getApiErrorMessage(error, "模型同步失败，上次成功数据已保留")); }
    finally { setSyncing(false); }
  };

  const save = async (code: AiFunctionCode) => {
    const draft = drafts[code];
    if (!draft) return;
    setSaving(code); setSaveErrors(previous => ({ ...previous, [code]: undefined }));
    try {
      const row = await adminApi.saveFunctionModelConfig(code, draft);
      setData(previous => previous ? { ...previous, functions: previous.functions.map(item => item.functionCode === code ? row : item) } : previous);
      setDrafts(previous => ({ ...previous, [code]: draftOf(row, draft.catalogVersion) }));
      toast.success("配置已保存，将用于新的 AI 任务");
    } catch (error) { setSaveErrors(previous => ({ ...previous, [code]: getApiErrorMessage(error, "保存失败，编辑内容已保留") })); }
    finally { setSaving(null); }
  };

  const reread = async (code: AiFunctionCode) => {
    setSaving(code);
    try {
      const next = await adminApi.modelConfiguration();
      const row = next.functions.find(item => item.functionCode === code);
      setData(next);
      if (row) setDrafts(previous => ({ ...previous, [code]: draftOf(row, next.catalogVersion) }));
      setSaveErrors(previous => ({ ...previous, [code]: undefined }));
    } catch (error) { setSaveErrors(previous => ({ ...previous, [code]: getApiErrorMessage(error, "重新读取失败") })); }
    finally { setSaving(null); }
  };

  if (loading || !data || readError) return <AdminDataState loading={loading} error={readError} retry={() => void load()} />;
  const busy = syncing || saving !== null;
  return <div className="space-y-6">
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div>
        <h2 className="text-lg font-semibold">模型配置</h2>
        <p className="mt-1 text-sm text-slate-500">按玩法与角色独立选择模型，保存后用于新的 AI 任务。</p>
        <p className="mt-2 text-xs text-slate-500">上次成功同步：{data.syncedAt ? new Date(data.syncedAt).toLocaleString("zh-CN") : "尚未同步"}</p>
      </div>
      <Button onClick={() => void sync()} disabled={busy}><RefreshCcw className={`mr-2 h-4 w-4 ${syncing ? "animate-spin" : ""}`} />{syncing ? "正在同步…" : "手动同步模型"}</Button>
    </div>
    {syncError && <p role="alert" className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">{syncError}。上次成功数据和编辑内容已保留。</p>}
    {!data.syncedAt && <p className="rounded-md bg-blue-50 p-3 text-sm text-blue-800">请先同步模型目录。同步前沿用环境默认模型；首次同步将导入当前模型，思考设置统一为跟随服务默认。</p>}

    <Card className="border-slate-200">
      <CardHeader><CardTitle className="text-base">功能配置</CardTitle></CardHeader>
      <CardContent className="divide-y divide-slate-200">
        {data.functions.map(row => {
          const draft = drafts[row.functionCode] || draftOf(row, data.catalogVersion);
          const model = data.models.find(item => item.modelKey === draft.modelKey);
          const modes = allowedModes(model);
          const options = model?.thinking?.declared ? model.thinking.options : [];
          const valid = !!model && modes.includes(draft.thinkingMode) && (draft.thinkingEffort === null || draft.thinkingMode === "ENABLED" && options.some(option => option.effort === draft.thinkingEffort));
          const change = (patch: Partial<SaveFunctionModelConfig>) => setDrafts(previous => ({ ...previous, [row.functionCode]: { ...draft, ...patch } }));
          const id = row.functionCode;
          return <section key={id} aria-label={row.label} className="space-y-3 py-5 first:pt-0 last:pb-0">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="font-medium">{row.label}</h3>
              <Badge variant={row.status === "INVALID" ? "destructive" : "secondary"}>{row.status === "INVALID" ? "待调整" : row.status === "NOT_SYNCED" ? "待同步" : "已配置"}</Badge>
              <span className="text-xs text-slate-500">{row.inferenceMessage}</span>
            </div>
            <div className="grid gap-3 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1fr)_auto]">
              <div className="space-y-2"><Label htmlFor={`${id}-model`}>模型</Label>
                <select id={`${id}-model`} className={selectClass} value={draft.modelKey} disabled={busy || !data.syncedAt} onChange={event => change({ modelKey: event.target.value, thinkingMode: "DEFAULT", thinkingEffort: null })}>
                  <option value="">请选择模型</option>
                  {!model && draft.modelKey && <option value={draft.modelKey} disabled>{draft.modelKey}（{data.syncedAt ? "已失效" : "当前值"}）</option>}
                  {data.models.map(item => <option key={item.modelKey} value={item.modelKey}>{item.displayName} · {item.modelKey}</option>)}
                </select>
              </div>
              <div className="space-y-2"><Label htmlFor={`${id}-mode`}>思考方式</Label>
                <select id={`${id}-mode`} className={selectClass} value={draft.thinkingMode} disabled={busy || !model} onChange={event => change({ thinkingMode: event.target.value as ThinkingMode, thinkingEffort: null })}>
                  {!modes.includes(draft.thinkingMode) && <option value={draft.thinkingMode} disabled>{modeLabels[draft.thinkingMode]}（已失效）</option>}
                  {modes.map(mode => <option key={mode} value={mode}>{modeLabels[mode]}</option>)}
                </select>
              </div>
              <div className="space-y-2"><Label htmlFor={`${id}-effort`}>思考强度</Label>
                <select id={`${id}-effort`} className={selectClass} value={draft.thinkingEffort || ""} disabled={busy || draft.thinkingMode !== "ENABLED" || !modes.includes("ENABLED")} onChange={event => change({ thinkingEffort: event.target.value ? event.target.value as ThinkingEffort : null })}>
                  <option value="">服务默认强度</option>
                  {draft.thinkingEffort && !options.some(option => option.effort === draft.thinkingEffort) && <option value={draft.thinkingEffort} disabled>{draft.thinkingEffort}（已失效）</option>}
                  {options.map(option => <option key={option.effort} value={option.effort}>{option.label}</option>)}
                </select>
              </div>
              <div className="flex items-end"><Button onClick={() => void save(id)} disabled={busy || !data.syncedAt || !valid || sameSettings(draft, row)}><Save className="mr-2 h-4 w-4" />{saving === id ? "保存中…" : "保存配置"}</Button></div>
            </div>
            {row.status === "INVALID" && <p className="text-sm text-red-700">{row.statusMessage}。配置已保留，请调整后保存。</p>}
            {model && (!model.thinking || !model.thinking.declared) && <p className="text-xs text-slate-500">该模型的思考能力未知，仅支持跟随服务默认。</p>}
            {saveErrors[id] && <div role="alert" className="flex flex-wrap items-center gap-2 text-sm text-red-700"><span>{saveErrors[id]}，编辑内容已保留。</span><Button size="sm" variant="outline" disabled={busy} onClick={() => void reread(id)}>重新读取此项</Button></div>}
          </section>;
        })}
      </CardContent>
    </Card>

    <Card className="border-slate-200">
      <CardHeader><CardTitle className="text-base">已同步模型 <span className="ml-1 text-sm font-normal text-slate-500">{data.models.length} 个</span></CardTitle></CardHeader>
      <CardContent>{data.models.length ? <div className="overflow-x-auto"><table className="w-full text-left text-sm">
        <thead className="border-b text-xs text-slate-500"><tr><th className="pb-3 pr-4 font-medium">模型</th><th className="pb-3 pr-4 font-medium">思考能力</th><th className="pb-3 pr-4 font-medium">支持的强度</th><th className="pb-3 font-medium">模型默认</th></tr></thead>
        <tbody className="divide-y">{data.models.map(model => <tr key={model.modelKey}>
          <td className="py-3 pr-4"><p className="font-medium">{model.displayName}</p><p className="mt-1 text-xs text-slate-500">{model.modelKey} · {model.provider}</p></td>
          <td className="py-3 pr-4">{!model.thinking?.declared ? "能力未知" : [model.thinking.supportsEnable && "可开启", model.thinking.supportsDisable && "可关闭"].filter(Boolean).join(" / ") || "不支持显式开关"}</td>
          <td className="py-3 pr-4">{model.thinking?.declared && model.thinking.options.length ? model.thinking.options.map(option => option.label).join("、") : "无可选强度"}</td>
          <td className="py-3 text-xs text-slate-500">{defaultThinking(model)}</td>
        </tr>)}</tbody>
      </table></div> : <p className="text-sm text-slate-500">{data.syncedAt ? "本次同步未返回可见模型，已有配置已保留并标记待调整。" : "尚未同步模型目录。"}</p>}</CardContent>
    </Card>
  </div>;
}
