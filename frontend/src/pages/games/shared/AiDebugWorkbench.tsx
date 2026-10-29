import { Fragment, useEffect, useRef, useState } from "react";
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { ArrowLeft, ArrowDownToLine, Check, Clipboard, Search, RefreshCw, Bug, ChevronRight } from "lucide-react";
import { toast } from "sonner";
import { DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { localAiDebugApi } from "@/services/api";
import type { AiDebugAttempt, AiDebugDetail, AiDebugTurn, AiDebugUsage } from "@/types/aiDebug";
import { roomPhase } from "./roomText";
import "./ai-debug.css";

const statusText: Record<string, string> = { QUEUED: "排队中", RUNNING: "生成中", SUCCEEDED: "已采用", FAILED: "失败", DISCARDED: "已丢弃" };
const kindText: Record<string, string> = { SPEAK: "发言", VOTE: "投票", NIGHT_ACTION: "夜间行动", HOST: "主持", ASK_QUESTION: "提问", ANSWER_PLAYER: "回应玩家", ANSWER: "回答", DISCUSS: "讨论", SUBMIT_SOLUTION: "提交答案" };
const tokenText = (value: number | null | undefined) => value == null ? "—" : value.toLocaleString("zh-CN");
const phaseText = (phase: string) => roomPhase(phase, "zh-CN");
const actorText = (actor: { actorName: string; seatNumber: number | null }) => `${actor.seatNumber == null ? "" : `${actor.seatNumber + 1} 号 · `}${actor.actorName}`;
const timeText = (time?: string) => time ? new Date(time).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" }) : "";

function Usage({ value }: { value?: AiDebugUsage }) {
  return <div className="ai-debug-usage" aria-label="所选对局 token 汇总">
    <div><span>输入 token</span><strong>{tokenText(value?.promptTokens)}</strong></div>
    <div><span>缓存读取 <small>包含于输入</small></span><strong>{tokenText(value?.cacheReadTokens)}</strong></div>
    <div><span>输出 token <small>包含思考</small></span><strong>{tokenText(value?.completionTokens)}</strong></div>
    <p>{value?.turns ?? 0} 次行动 · {value?.attempts ?? 0} 次尝试 · {value?.responses ?? 0} 次响应 <span>{value?.authoritative === true ? "权威用量" : value?.authoritative === false ? "含估算用量" : "用量来源未完全确认"}{value && !value.complete ? " · 部分用量未知" : ""}</span></p>
  </div>;
}

function Notice({ children, retry }: { children: React.ReactNode; retry?: () => void }) {
  return <div className="ai-debug-notice" role="status">{children}{retry && <button onClick={retry}>重试</button>}</div>;
}

export default function AiDebugWorkbench(props: { gameId: string; roomId: string; roomName: string; userKey: string; instanceId?: string }) {
  const scope = ["local-ai-debug", props.userKey, props.gameId, props.roomId];
  const [chosenInstance, setChosenInstance] = useState("");
  const [round, setRound] = useState("all");
  const [actor, setActor] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [chosenTurn, setChosenTurn] = useState<string | null>(null);
  const [follow, setFollow] = useState(true);
  const [mobileDetail, setMobileDetail] = useState(false);
  const instances = useInfiniteQuery({
    queryKey: [...scope, "instances"], initialPageParam: 0,
    queryFn: ({ pageParam, signal }) => localAiDebugApi.instances(props.gameId, props.roomId, pageParam, signal),
    getNextPageParam: last => (last.page + 1) * last.size < last.total ? last.page + 1 : undefined,
  });
  const instanceList = instances.data?.pages.flatMap(p => p.items) ?? [];
  const instanceId = chosenInstance || props.instanceId || instanceList[0]?.id || "";
  const summary = useQuery({ queryKey: [...scope, "summary", instanceId], enabled: !!instanceId,
    queryFn: ({ signal }) => localAiDebugApi.summary(props.gameId, props.roomId, instanceId, signal), refetchInterval: 2000 });
  const split = round.indexOf(":");
  const roundNumber = round === "all" ? undefined : Number(round.slice(0, split));
  const phase = round === "all" ? undefined : round.slice(split + 1);
  const turns = useQuery({ queryKey: [...scope, "turns", instanceId, round, actor, status, page], enabled: !!instanceId,
    queryFn: ({ signal }) => localAiDebugApi.turns(props.gameId, props.roomId, { instanceId, round: roundNumber, phase, actorId: actor || undefined, status: status || undefined, page }, signal),
    refetchInterval: 2000 });
  const rows = turns.data?.items ?? [];
  const selectedId = follow ? rows[0]?.id : chosenTurn || rows[0]?.id;
  const selected = rows.find(t => t.id === selectedId);
  const detail = useQuery({ queryKey: [...scope, "detail", selectedId], enabled: !!selectedId,
    queryFn: ({ signal }) => localAiDebugApi.detail(props.gameId, props.roomId, selectedId!, signal), gcTime: 0 });
  const { refetch } = detail;
  const detailUpdated = detail.data?.turn.updatedAt;
  const detailStatus = detail.data?.turn.status;
  useEffect(() => {
    if (selectedId && detailUpdated && selected && (detailUpdated !== selected.updatedAt || detailStatus !== selected.status)) void refetch();
  }, [selectedId, detailUpdated, detailStatus, selected, refetch]);
  const reset = () => { setPage(0); setChosenTurn(null); setMobileDetail(false); };
  const chooseRound = (value: string) => { setRound(value); setFollow(false); reset(); };
  const rounds = summary.data?.rounds ?? [];
  const roundsSelect = <select aria-label="回合与阶段" value={round} onChange={e => chooseRound(e.target.value)}>
    <option value="all">全部回合与阶段</option>{rounds.map(r => <option key={`${r.round}:${r.phase}`} value={`${r.round}:${r.phase}`}>第 {r.round} 轮 · {phaseText(r.phase)}</option>)}
  </select>;
  const totalPages = Math.max(1, Math.ceil((turns.data?.total ?? 0) / 50));
  const refresh = () => { void instances.refetch(); void summary.refetch(); void turns.refetch(); if (selectedId) void detail.refetch(); };
  return <DialogContent className="ai-debug-workbench" data-testid="ai-debug-workbench">
    <header className="ai-debug-header"><div className="ai-debug-mark"><Bug size={18} /></div><div><DialogTitle>AI 调试工作台 <span>LOCAL</span></DialogTitle><DialogDescription>{props.roomName} · 按回合检查模型实际收到和返回的内容</DialogDescription></div>
      <button className="ai-debug-refresh" aria-label="刷新调试记录" onClick={refresh}><RefreshCw size={15} /></button>
    </header>
    <div className="ai-debug-instance"><label htmlFor="ai-debug-instance">对局</label><select id="ai-debug-instance" value={instanceId} onChange={e => { setChosenInstance(e.target.value); setRound("all"); setActor(""); setStatus(""); setFollow(false); reset(); }}>
      {!instanceList.some(i => i.id === instanceId) && <option value={instanceId}>{instanceId ? `当前对局 · ${instanceId.slice(0, 12)}` : "暂无对局"}</option>}
      {instanceList.map(i => <option value={i.id} key={i.id}>{new Date(i.startedAt).toLocaleString("zh-CN")} · {i.turnCount} 次行动 · {i.id.slice(0, 10)}</option>)}
    </select>{instances.hasNextPage && <button onClick={() => void instances.fetchNextPage()} disabled={instances.isFetchingNextPage}>更早对局</button>}
      <label className="ai-debug-follow"><input type="checkbox" checked={follow} onChange={e => { setFollow(e.target.checked); setPage(0); setChosenTurn(null); }} /><ArrowDownToLine size={13} />跟随最新</label>
    </div>
    <Usage value={summary.data?.usage} />
    {(instances.isError || summary.isError || turns.isError) && <Notice retry={refresh}>读取记录失败，已显示的内容仍可查看。</Notice>}
    {summary.data?.captureWarning && <Notice>部分记录采集失败。缺失内容不会补造，请结合调用状态分析。</Notice>}
    <div className="ai-debug-workspace" data-mobile-detail={mobileDetail}>
      <nav className="ai-debug-rounds" aria-label="回合导航"><h3>回合与阶段</h3><button aria-pressed={round === "all"} onClick={() => chooseRound("all")}><span>全部回合</span><small>{summary.data?.usage.turns ?? 0} 次行动</small></button>
        {rounds.map(r => <button key={`${r.round}:${r.phase}`} aria-pressed={round === `${r.round}:${r.phase}`} onClick={() => chooseRound(`${r.round}:${r.phase}`)}>
          <span>第 {r.round} 轮 <i>{r.problems > 0 ? `${r.problems} 异常` : ""}</i></span><strong>{phaseText(r.phase)}</strong><small>{r.usage.turns} 次行动 · {tokenText(r.usage.promptTokens)} 输入</small>
        </button>)}{rounds.length === 0 && <p>AI 开始行动后，回合会出现在这里。</p>}
      </nav>
      <section className="ai-debug-turns" aria-label="角色行动列表">
        <div className="ai-debug-filters"><div className="ai-debug-round-select">{roundsSelect}</div><div><select aria-label="筛选角色" value={actor} onChange={e => { setActor(e.target.value); setFollow(false); reset(); }}><option value="">全部角色</option>{summary.data?.actors.map(a => <option key={a.id} value={a.id}>{a.seatNumber == null ? "" : `${a.seatNumber + 1} 号 · `}{a.name}</option>)}</select>
          <select aria-label="筛选状态" value={status} onChange={e => { setStatus(e.target.value); setFollow(false); reset(); }}><option value="">全部状态</option>{Object.entries(statusText).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div>
          <p><span>{turns.data?.total ?? 0} 次行动</span><span>时间从新到旧</span></p></div>
        <div className="ai-debug-turn-scroll">{rows.map(t => <button key={t.id} className="ai-debug-turn" data-testid="ai-debug-turn" aria-pressed={selectedId === t.id} onClick={() => { setChosenTurn(t.id); setFollow(false); setMobileDetail(true); }}>
          <div><strong>{actorText(t)}</strong><span className="ai-debug-status" data-status={t.status}>{statusText[t.status] || t.status}</span></div>
          <p><span>第 {t.round} 轮 · {phaseText(t.phase)} · {kindText[t.kind] || t.kind}</span><time>{timeText(t.createdAt)}</time></p>
          <p className="ai-debug-model"><span>{t.modelKey || "等待模型选择"}</span><span>{t.latencyMs == null ? "" : `${(t.latencyMs / 1000).toFixed(1)}s`}{t.attemptCount > 1 ? ` · ${t.attemptCount} 次尝试` : ""}</span></p>
          <div className="ai-debug-row-tokens"><span>输入 <b>{tokenText(t.promptTokens)}</b></span><span>缓存 <b>{tokenText(t.cacheReadTokens)}</b></span><span>输出 <b>{tokenText(t.completionTokens)}</b></span></div>

        </button>)}{turns.isPending && instanceId && <Notice>正在读取行动…</Notice>}{!turns.isPending && rows.length === 0 && <div className="ai-debug-empty"><Bug size={28} /><h3>{instanceId ? "尚无匹配的 AI 行动" : "等待对局开始"}</h3><p>每次 AI 行动及修正尝试会自动记录。</p></div>}</div>
        <footer className="ai-debug-pagination"><button disabled={page === 0} onClick={() => { setPage(page - 1); setChosenTurn(null); setFollow(false); }}>上一页</button><span>{page + 1} / {totalPages}</span><button disabled={page + 1 >= totalPages} onClick={() => { setPage(page + 1); setChosenTurn(null); setFollow(false); }}>下一页</button></footer>
      </section>
      <section className="ai-debug-detail" aria-label="调用详情"><button className="ai-debug-mobile-back" onClick={() => setMobileDetail(false)}><ArrowLeft size={15} />返回行动列表</button>
        {detail.isError ? <Notice retry={() => void detail.refetch()}>详情读取失败。</Notice> : detail.data && selectedId ? <TurnDetail key={selectedId} detail={detail.data} /> : <div className="ai-debug-empty"><ChevronRight size={28} /><h3>{detail.isPending && selectedId ? "正在读取详情…" : "选择一次行动"}</h3><p>输入、思考和输出将显示在这里。</p></div>}
      </section>
    </div>
  </DialogContent>;
}

function TurnDetail({ detail }: { detail: AiDebugDetail }) {
  const { turn, data } = detail;
  const attempts = Object.values(data.attempts || {}).sort((a, b) => a.attempt - b.attempt);
  const [attempt, setAttempt] = useState(0);
  const [tab, setTab] = useState("input");
  const [search, setSearch] = useState("");
  const [wrap, setWrap] = useState(true);
  const [structured, setStructured] = useState(true);
  const content = useRef<HTMLDivElement>(null);
  const [matchCount, setMatchCount] = useState(0);
  const [activeMatch, setActiveMatch] = useState(0);
  const moveMatch = (offset: number) => {
    const matches = Array.from(content.current?.querySelectorAll("mark") || []);
    if (!matches.length) return;
    const index = (activeMatch + offset + matches.length) % matches.length;
    matches.forEach((match, i) => match.toggleAttribute("data-active", i === index));
    matches[index].scrollIntoView?.({ block: "center" }); setActiveMatch(index);
  };
  useEffect(() => {
    const matches = Array.from(content.current?.querySelectorAll("mark") || []);
    setMatchCount(matches.length); setActiveMatch(0);
    matches[0]?.setAttribute("data-active", ""); matches[0]?.scrollIntoView?.({ block: "center" });
  }, [search, tab, attempt, structured]);
  const selected = attempts.find(a => a.attempt === attempt);
  const parsed = selected?.parsedOutput;
  const basis = parsed ? Object.fromEntries(Object.entries(parsed).filter(([key]) => /reason|reflect|evidence|memory|belief|confidence/i.test(key))) : {};
  return <>
    <div className="ai-debug-detail-heading"><div><h2>{actorText(turn)}</h2><p>第 {turn.round} 轮 · {phaseText(turn.phase)} · {kindText[turn.kind] || turn.kind}</p></div><span className="ai-debug-status" data-status={turn.status}>{statusText[turn.status] || turn.status}</span></div>
    <div className="ai-debug-attempts" aria-label="生成尝试">{attempts.map(a => <button key={a.attempt} aria-pressed={attempt === a.attempt} onClick={() => setAttempt(a.attempt)}>{a.attempt === 0 ? "首次生成" : `修正 / 重试 ${a.attempt}`}<small>{a.state === "WAITING_BUDGET" ? "额度等待" : a.flags?.length ? "异常" : a.state === "RESPONSE" ? "已返回" : a.state === "REQUESTING" ? "进行中" : a.state === "NOT_SENT" ? "未发出" : "失败"}</small></button>)}</div>
    <div className="ai-debug-tabs" role="tablist" aria-label="调试详情分类">{[["input", "输入"], ["thinking", "思考"], ["output", "输出"], ["call", "调用详情"]].map(([id, label]) => <button key={id} role="tab" id={`ai-debug-tab-${id}`} aria-controls="ai-debug-panel" aria-selected={tab === id} tabIndex={tab === id ? 0 : -1} onClick={() => setTab(id)} onKeyDown={e => {
      const tabs = ["input", "thinking", "output", "call"];
      if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key)) return;
      e.preventDefault(); const next = e.key === "Home" ? tabs[0] : e.key === "End" ? tabs[3] : tabs[(tabs.indexOf(tab) + (e.key === "ArrowLeft" ? 3 : 1)) % 4];
      setTab(next); document.getElementById(`ai-debug-tab-${next}`)?.focus();
    }}>{label}</button>)}</div>
    <div className="ai-debug-text-tools"><label><Search size={14} /><input aria-label="查找详情文本" placeholder="查找文本" value={search} onChange={e => setSearch(e.target.value)} onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); moveMatch(e.shiftKey ? -1 : 1); } }} /></label>{search && <><small aria-live="polite">{matchCount ? activeMatch + 1 : 0}/{matchCount}</small><button className="ai-debug-search-next" aria-label="下一个匹配" title="Enter 下一个，Shift+Enter 上一个" onClick={() => moveMatch(1)}><ChevronRight size={14} /></button></>}<label><input type="checkbox" checked={wrap} onChange={e => setWrap(e.target.checked)} />自动换行</label>{tab === "input" && <label><input type="checkbox" checked={structured} onChange={e => setStructured(e.target.checked)} />结构视图</label>}</div>
    <div id="ai-debug-panel" role="tabpanel" ref={content} aria-labelledby={`ai-debug-tab-${tab}`} className="ai-debug-content" data-wrap={wrap}>

      {tab === "input" && <>{selected?.messages?.length ? selected.messages.map((message, index) => <details className="ai-debug-message" key={`${selected.attempt}:${index}`} open={message.role !== "system"}>
        <summary>{message.role === "system" ? "系统提示" : message.role === "user" ? "角色输入" : message.role}<span>{message.content.length.toLocaleString()} 字符</span></summary>
        <TextBlock text={message.content} title={`输入 ${message.role}`} search={search} structured={structured} />
      </details>) : <Notice>未保存请求原文。</Notice>}</>}
      {tab === "thinking" && <><section className="ai-debug-section"><h3>上游思考{selected?.reasoningKind === "SUMMARY" ? "摘要" : "文本"}<span>供应商实际返回</span></h3>{selected?.reasoningText ? <TextBlock title="思考" text={selected.reasoningText} search={search} /> : <Notice>{selected?.reasoningKind === "GATEWAY_NOT_PROVIDED" ? "当前响应未提供可展示的调试字段。" : "本次没有返回可展示的思考文本。推理 token 数不能还原思考内容。"}</Notice>}</section>
        <section className="ai-debug-section"><h3>角色决策依据与反思<span>结构化输出字段</span></h3>{Object.keys(basis).length ? <JsonTree value={basis} label="决策依据" search={search} /> : <Notice>没有已保存的决策依据或反思字段。</Notice>}</section></>}
      {tab === "output" && <><section className="ai-debug-section"><h3>模型原始正文</h3>{selected?.rawOutput !== undefined ? <TextBlock title="原始输出" text={selected.rawOutput} search={search} /> : <Notice>模型正文尚未返回或未保存。</Notice>}</section>{parsed && <section className="ai-debug-section"><h3>解析结果</h3><JsonTree value={parsed} label="结构化输出" search={search} /></section>}
        <section className="ai-debug-section"><h3>本局采用的动作</h3>{data.appliedAction ? <JsonTree value={data.appliedAction} label="实际执行动作" search={search} /> : <Notice>{turn.status === "SUCCEEDED" ? "没有采集到实际执行动作。" : "此结果尚未用于本局，或已失败、超时、丢弃。"}{turn.terminalReason ? ` ${turn.terminalReason}` : ""}</Notice>}</section></>}
      {tab === "call" && <><CallUsage attempt={selected} turn={turn} /><JsonTree label="调用参数与结果" search={search} value={{ requestId: selected?.requestId, state: selected?.state, modelKey: selected?.modelKey || turn.modelKey, modelSelection: data.modelSelection, promptVersion: data.promptVersion, inputFormatVersion: data.inputFormatVersion, requestTimeoutMs: selected?.requestTimeoutMs, latencyMs: selected?.latencyMs, parseMs: selected?.parseMs, validationMs: selected?.validationMs, flags: selected?.flags, failureStage: selected?.failureStage, finishReason: selected?.finishReason, budgetId: selected?.budgetId, settlementState: selected?.settlementState, terminalReason: turn.terminalReason }} /></>}
    </div>
  </>;
}

function CallUsage({ attempt, turn }: { attempt?: AiDebugAttempt; turn: AiDebugTurn }) {
  return <section className="ai-debug-section"><h3>{attempt ? "本次尝试用量" : "已保存行动用量"}<span>{attempt?.usageAuthoritative === true ? "权威" : attempt?.usageAuthoritative === false ? "估算" : "来源未确认"}</span></h3><dl className="ai-debug-call-usage">{[
    ["输入", attempt ? attempt.promptTokens : turn.promptTokens], ["缓存读取（输入子集）", attempt ? attempt.cacheReadTokens : turn.cacheReadTokens], ["缓存创建（输入子集）", attempt ? attempt.cacheWriteTokens : turn.cacheWriteTokens],
    ["输出（含推理）", attempt ? attempt.completionTokens : turn.completionTokens], ["推理（输出子集）", attempt ? attempt.reasoningTokens : turn.reasoningTokens],
  ].map(([label, value]) => <Fragment key={label}><dt>{label}</dt><dd>{tokenText(value as number | undefined)}</dd></Fragment>)}</dl><p className="ai-debug-footnote">缓存和推理已计入输入、输出总量；“—”表示未知，不代表零。</p></section>;
}

function TextBlock({ text, title, search, structured = false }: { text: string; title: string; search: string; structured?: boolean }) {
  const [copied, setCopied] = useState(false);
  let parsed: unknown;
  if (structured) try { parsed = JSON.parse(text); } catch { /* Preserve the exact text when it is not JSON. */ }
  return <div className="ai-debug-text-block"><button className="ai-debug-copy" aria-label={`复制${title}`} onClick={async () => { try { await navigator.clipboard.writeText(text); setCopied(true); } catch { toast.error("复制失败，请选择文本手动复制。"); } }}>{copied ? <Check size={14} /> : <Clipboard size={14} />}{copied ? "已复制" : "复制"}</button>
    {parsed !== undefined ? <JsonTree value={parsed} label="模型实际输入" search={search} /> : <pre>{highlight(text || "（空正文）", search)}</pre>}</div>;
}

function highlight(text: string, search: string) {
  if (!search) return text;
  const escaped = search.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return text.split(new RegExp(`(${escaped})`, "gi")).map((part, i) => i % 2 ? <mark key={i}>{part}</mark> : part);
}

function JsonTree({ value, label, search, depth = 0 }: { value: unknown; label: string; search: string; depth?: number }) {
  const [open, setOpen] = useState(depth === 0 || !!search);
  if (value === undefined) return null;
  if (value === null || typeof value !== "object") return <div className="ai-debug-json-value"><span>{label}</span><pre>{highlight(typeof value === "string" ? value : String(value), search)}</pre></div>;
  const entries = Object.entries(value);
  return <details className="ai-debug-json" open={open || !!search} onToggle={e => setOpen(e.currentTarget.open)}><summary>{label}<small>{Array.isArray(value) ? `${entries.length} 项` : `${entries.length} 个字段`}</small></summary>
    {(open || !!search) && <div>{entries.map(([key, child]) => <JsonTree key={key} value={child} label={key} search={search} depth={depth + 1} />)}</div>}</details>;
}
