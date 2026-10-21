import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FlaskConical, Plus, ArrowLeft, Play, Pause, Square, RefreshCcw, GitCompareArrows } from "lucide-react";
import { toast } from "sonner";
import { adminApi, adminLabApi, personaApi, getApiErrorMessage } from "@/services/api";
import { Button } from "@/components/ui/button";
import { AdminDataState } from "@/components/admin/AdminDataState";
import { LabExperimentRows, LabCostPanel, LabProgress, LabLiveMatches, LabMatchHistory, LabEvaluationResults, LabPager } from "@/components/lab/LabPanels";
import { ModelSelectionFields } from "@/components/lab/ModelSelectionFields";
import type { CatalogModel } from "@/types/aiModelConfig";
import type { LabAgent, LabSelection, LabExperience, CreateLabExperiment } from "@/types/lab";

const emptySelection:LabSelection={modelKey:"",thinkingMode:"DEFAULT",thinkingEffort:null};
function failure(error:unknown) { toast.error(getApiErrorMessage(error,"实验室操作失败，编辑内容已保留")); }

function AgentEditor({models,agents,reload}:{models:CatalogModel[];agents:LabAgent[];reload:()=>void}) {
  const personas=useQuery({queryKey:["admin","lab","personas"],queryFn:personaApi.list});
  const [editing,setEditing]=useState<string>();
  const [open,setOpen]=useState(false);
  const [name,setName]=useState("");
  const [personaId,setPersona]=useState("");
  const [selection,setSelection]=useState<LabSelection>(emptySelection);
  const save=useMutation({
    mutationFn:()=>adminLabApi.saveAgent({name,personaId,selection},editing),
    onSuccess:()=>{ reload(); setOpen(false); toast.success("AI 档案已保存，已开局的快照保持原版本"); },onError:failure,
  });
  const cohort=useMutation({
    mutationFn:async()=>{
      const model=models[0];
      if(!model||!personas.data?.length) throw new Error("请先同步模型和人格");
      for(let index=0;index<6;index++) {
        const persona=personas.data[index%personas.data.length];
        await adminLabApi.saveAgent({name:persona.name+" · "+String(index+1).padStart(2,"0"),personaId:persona.id,
          selection:{modelKey:model.modelKey,thinkingMode:"DEFAULT",thinkingEffort:null}});
      }
    },onSuccess:()=>{reload();toast.success("已创建 6 个独立 AI 档案");},onError:failure,
  });
  const edit=(agent?:LabAgent)=>{
    setEditing(agent?.id);setName(agent?.name??"");setPersona(agent?.personaId??personas.data?.[0]?.id??"");
    setSelection(agent?{...agent.modelSelection,thinkingEffort:agent.modelSelection.thinkingEffort??null}:{...emptySelection,modelKey:models[0]?.modelKey??""});setOpen(true);
  };
  return <section><div className="lab-actions justify-between"><p className="lab-small">档案独立配置模型、思考和人格。实验会保存开局快照。</p><div className="lab-actions">
    {!agents.length&&<Button size="sm" variant="outline" disabled={!models.length||!personas.data?.length||cohort.isPending} onClick={()=>cohort.mutate()}>创建 6 个初始 AI</Button>}
    <Button size="sm" onClick={()=>edit()} disabled={!models.length}><Plus size={14} className="mr-1" />新建 AI</Button></div></div>
    {open&&<form className="lab-form space-y-4" onSubmit={event=>{event.preventDefault();save.mutate();}}><h2 className="font-semibold">{editing?"编辑 AI 档案":"新建 AI 档案"}</h2>
      <div className="lab-form-grid"><label className="lab-field">档案名称<input value={name} maxLength={64} required onChange={event=>setName(event.target.value)} /></label>
        <label className="lab-field">人格<select value={personaId} required onChange={event=>setPersona(event.target.value)}><option value="">选择人格</option>{personas.data?.map(persona=><option key={persona.id} value={persona.id}>{persona.name} · {persona.trait}</option>)}</select></label></div>
      <ModelSelectionFields models={models} value={selection} change={setSelection} prefix="agent" />
      {save.error&&<p className="lab-error" role="alert">{getApiErrorMessage(save.error,"保存失败")}</p>}
      <div className="lab-actions"><Button disabled={save.isPending||!selection.modelKey} type="submit">保存档案</Button><Button variant="ghost" type="button" onClick={()=>setOpen(false)}>取消</Button></div>
    </form>}
    <div className="lab-list mt-5">{agents.map(agent=><div className="lab-list-row" key={agent.id}><div><h3>{agent.name}</h3><p className="lab-small">{personas.data?.find(persona=>persona.id===agent.personaId)?.name??agent.personaId}</p></div><span className="lab-small break-all">{agent.modelSelection.modelKey}</span><span className="lab-small">{agent.modelSelection.thinkingMode} {agent.modelSelection.thinkingEffort}</span><Button size="sm" variant="outline" onClick={()=>edit(agent)}>编辑</Button></div>)}</div>
  </section>;
}
function AdminLabIndex() {
  const client=useQueryClient(),navigate=useNavigate();
  const [params]=useSearchParams();
  const from=params.get("from")??"";
  const [tab,setTab]=useState<"experiments"|"agents">("experiments");
  const [creating,setCreating]=useState(!!from);
  const [page,setPage]=useState(0);
  const [name,setName]=useState("卧底策略实验");
  const [selected,setSelected]=useState<string[]>([]);
  const [playerCount,setPlayerCount]=useState(6);
  const [targetGames,setTargetGames]=useState(100);
  const [concurrency,setConcurrency]=useState(1);
  const [wordPack,setWordPack]=useState("daily");
  const [hasBlank,setHasBlank]=useState(false);
  const [budget,setBudget]=useState("10.00");
  const [coach,setCoach]=useState<LabSelection>(emptySelection);
  const [sourceId,setSourceId]=useState(from);
  const [inheritId,setInheritId]=useState(from);
  const list=useQuery({queryKey:["admin","lab","list",page],queryFn:()=>adminLabApi.list(page),refetchInterval:5000});
  const agents=useQuery({queryKey:["admin","lab","agents"],queryFn:adminLabApi.agents});
  const catalog=useQuery({queryKey:["admin","model-config"],queryFn:adminApi.modelConfiguration});
  const source=useQuery({queryKey:["admin","lab","source",inheritId],queryFn:()=>adminLabApi.detail(inheritId),enabled:!!inheritId});
  const sync=useMutation({mutationFn:adminApi.syncModelConfiguration,onSuccess:data=>{client.setQueryData(["admin","model-config"],data);toast.success("模型目录已同步");},onError:failure});
  const create=useMutation({
    mutationFn:async()=>{
      const chosen=selected.length?selected:from?source.data?.configuration?.roster.map(agent=>agent.agentId)??[]:[];
      const configured=coach.modelKey?coach:{...coach,modelKey:catalog.data?.functions.find(config=>config.functionCode==="UNDERCOVER")?.modelKey??catalog.data?.models[0]?.modelKey??""};
      const inherited=source.data?.configuration?.experiences??{};
      const request:CreateLabExperiment={name,agentIds:chosen,playerCount,targetGames,concurrency,wordPack,hasBlank,
        budgetPoints:Math.round(Number(budget)*100),coach:configured,
        initialExperienceIds:Object.fromEntries(chosen.filter(id=>inherited[id]&&inherited[id].experienceId!=="initial").map(id=>[id,inherited[id].experienceId]))};
      return adminLabApi.create(request);
    },
    onSuccess:experiment=>{void client.invalidateQueries({queryKey:["admin","lab"]});navigate("/admin/lab/"+experiment.id);},onError:failure,
  });
  const chosen=selected.length?selected:from?source.data?.configuration?.roster.map(agent=>agent.agentId)??[]:[];
  const models=catalog.data?.models??[];
  const newExperiment=()=>{setCreating(true);setSelected((agents.data??[]).slice(0,6).map(agent=>agent.id));setCoach({...emptySelection,modelKey:catalog.data?.functions.find(config=>config.functionCode==="UNDERCOVER")?.modelKey??models[0]?.modelKey??""});};
  return <div className="lab-workspace">
    <header className="lab-heading"><div><div className="lab-eyebrow">NEXUS / RESEARCH CONTROL</div><h1>AI 实验室</h1><p>以完整对局积累经验，用固定对手检验变化。</p></div>
      <div className="lab-actions"><Button variant="outline" size="sm" onClick={()=>sync.mutate()} disabled={sync.isPending}><RefreshCcw size={14} className="mr-2" />同步模型</Button><Button size="sm" onClick={newExperiment}><Plus size={14} className="mr-2" />新建实验</Button></div></header>
    <div className="lab-tabs" role="tablist"><button role="tab" aria-selected={tab==="experiments"} onClick={()=>setTab("experiments")}>实验任务</button><button role="tab" aria-selected={tab==="agents"} onClick={()=>setTab("agents")}>独立 AI 档案 · {agents.data?.length??0}</button></div>
    {catalog.error&&<div className="lab-error" role="alert">{getApiErrorMessage(catalog.error,"模型目录读取失败")}</div>}
    {!models.length&&<p className="lab-note">请先同步模型目录，再创建独立 AI 档案。目录读取不会产生推理费用。</p>}
    {creating&&<form className="lab-form space-y-5" onSubmit={(event:FormEvent)=>{event.preventDefault();create.mutate();}}><h2 className="font-semibold">新建卧底实验</h2>
      <div className="lab-form-grid"><label className="lab-field">实验名称<input value={name} maxLength={100} required onChange={event=>setName(event.target.value)} /></label>
        <label className="lab-field">席位数<select value={playerCount} onChange={event=>setPlayerCount(Number(event.target.value))}>{[4,5,6,7,8,9,10].map(count=><option key={count}>{count}</option>)}</select></label>
        <label className="lab-field">目标对局数<input type="number" min={1} max={10000} value={targetGames} onChange={event=>setTargetGames(Number(event.target.value))} required /></label>
        <label className="lab-field">并发桌数<input type="number" min={1} max={3} value={concurrency} onChange={event=>setConcurrency(Number(event.target.value))} required /></label>
        <label className="lab-field">内置词库<select value={wordPack} onChange={event=>setWordPack(event.target.value)}><option value="daily">日常生活</option><option value="idiom">成语俗语</option><option value="acg">二次元</option><option value="tech">硬核科技</option></select></label>
        <label className="lab-field">费用上限（人民币）<input type="number" min="0.01" step="0.01" value={budget} required onChange={event=>setBudget(event.target.value)} /></label>
        <label className="lab-field"><input type="checkbox" checked={hasBlank} onChange={event=>setHasBlank(event.target.checked)} />加入白板玩家</label></div>
      <div><h3 className="text-sm font-medium">实验阵容 · 已选 {chosen.length} / {playerCount}</h3><div className="lab-agent-choice">{agents.data?.map(agent=><label key={agent.id}><input type="checkbox" checked={chosen.includes(agent.id)} onChange={event=>setSelected(event.target.checked?[...chosen,agent.id]:chosen.filter(id=>id!==agent.id))} />{agent.name}</label>)}</div>
        {!agents.data?.length&&<p className="lab-small mt-2">先到“独立 AI 档案”创建阵容。</p>}</div>
      <div><h3 className="text-sm font-medium mb-3">统一复盘教练</h3><ModelSelectionFields models={models} value={coach.modelKey?coach:{...coach,modelKey:catalog.data?.functions.find(config=>config.functionCode==="UNDERCOVER")?.modelKey??models[0]?.modelKey??""}} change={setCoach} prefix="coach" /></div>
      <div className="lab-form-grid"><label className="lab-field">继承经验来源实验 ID（可选）<input value={sourceId} onChange={event=>setSourceId(event.target.value)} placeholder="空白表示从初始经验开始" /></label><Button variant="outline" className="self-end" type="button" onClick={()=>setInheritId(sourceId.trim())}>读取来源经验</Button></div>
      {source.data&&<p className="lab-small">将继承“{source.data.name}”中已选 AI 当前的经验版本。模型和人格从当前档案复制。</p>}
      {source.error&&<p className="lab-error" role="alert">{getApiErrorMessage(source.error,"来源实验读取失败")}</p>}
      <p className="lab-note">创建后为草稿，需要手动启动。对局、修正、复盘与评测共享费用上限；实验对局不计普通排名、成就和奖励。</p>
      {create.error&&<p className="lab-error" role="alert">{getApiErrorMessage(create.error,"创建失败")}</p>}
      <div className="lab-actions"><Button type="submit" disabled={create.isPending||chosen.length!==playerCount||!models.length}>创建实验草稿</Button><Button type="button" variant="ghost" onClick={()=>setCreating(false)}>取消</Button></div>
    </form>}
    {tab==="agents"?<AgentEditor models={models} agents={agents.data??[]} reload={()=>void agents.refetch()} />:
      list.data?<><LabExperimentRows rows={list.data.items} admin /><LabPager page={page} total={list.data.total} size={20} change={setPage} /></>:<AdminDataState loading={list.isPending} error={list.error} retry={()=>void list.refetch()} />}
  </div>;
}

function ExperienceComparison({versions,roster,heads,rollback,disabled}:{versions:LabExperience[];roster:{agentId:string;name:string}[];heads:Record<string,{experienceId:string}>;rollback:(id:string)=>void;disabled:boolean}) {
  const [selectedAgent,setAgent]=useState("");
  const [before,setBefore]=useState("");
  const [after,setAfter]=useState("");
  const agentId=selectedAgent||roster[0]?.agentId||"";
  const entries=versions.filter(version=>version.agentId===agentId);
  const earlier=entries.find(version=>version.id===before)||entries[1];
  const later=entries.find(version=>version.id===after)||entries[0];
  return <div><div className="lab-form-grid"><label className="lab-field">AI 档案<select value={agentId} onChange={event=>{setAgent(event.target.value);setBefore("");setAfter("");}}>{roster.map(agent=><option key={agent.agentId} value={agent.agentId}>{agent.name}</option>)}</select></label><p className="lab-small self-end">每个版本保留来源对局与证据事件。回退生成新版本，仅影响之后开局。</p></div>
    <div className="lab-compare">{[{title:"较早版本",value:earlier,choose:setBefore},{title:"比较版本",value:later,choose:setAfter}].map(column=><section key={column.title}><label className="lab-field">{column.title}<select value={column.value?.id??""} onChange={event=>column.choose(event.target.value)}><option value="">无版本</option>{entries.map(version=><option key={version.id} value={version.id}>v{version.sequenceNumber}{heads[agentId]?.experienceId===version.id?" · 当前":""} · {version.createdAt.replace("T"," ").slice(0,16)}</option>)}</select></label>
      <div className="lab-memory mt-3">{column.value?.summary||"当前页没有可比较的经验。"}</div>
      {column.value&&<div className="lab-actions mt-3">{column.value.evidence.archiveId&&<Button asChild size="sm" variant="outline"><Link to={"/replay/"+column.value.evidence.archiveId}>来源回放</Link></Button>}<Button size="sm" variant="outline" disabled={disabled} onClick={()=>rollback(column.value!.id)}>回退到此经验</Button></div>}</section>)}</div>
    {later&&<p className="lab-small">证据事件：{later.evidence.eventIds?.join(" · ")||"管理员回退"}{earlier?.summary===later.summary?" · 两版内容相同":""}</p>}
  </div>;
}
function AdminLabDetail({id}:{id:string}) {
  const client=useQueryClient();
  const [page,setPage]=useState(0),[experiencePage,setExperiencePage]=useState(0);
  const [agentId,setAgent]=useState(""),[candidate,setCandidate]=useState(""),[pairs,setPairs]=useState(20);
  const query=useQuery({queryKey:["admin","lab","experiment",id],queryFn:()=>adminLabApi.detail(id),refetchInterval:2000});
  const history=useQuery({queryKey:["admin","lab","matches",id,page],queryFn:()=>adminLabApi.matches(id,page),refetchInterval:5000});
  const versions=useQuery({queryKey:["admin","lab","experiences",id,experiencePage],queryFn:()=>adminLabApi.experiences(id,experiencePage),refetchInterval:5000});
  const refresh=()=>void client.invalidateQueries({queryKey:["admin","lab"]});
  const controls=useMutation({mutationFn:(action:string)=>action==="retry-review"?adminLabApi.retryReview(id):adminLabApi.control(id,action),onSuccess:refresh,onError:failure});
  const rollback=useMutation({mutationFn:(version:string)=>adminLabApi.rollback(id,version),onSuccess:()=>{refresh();toast.success("回退已保存为新版本");},onError:failure});
  const experiment=query.data;
  const configuration=experiment?.configuration;
  const roster=configuration?.roster??[];
  const target=agentId||roster[0]?.agentId||"";
  const targetVersion=candidate||configuration?.experiences[target]?.experienceId||"";
  const evaluate=useMutation({mutationFn:()=>adminLabApi.evaluate(id,target,targetVersion,pairs),onSuccess:refresh,onError:failure});
  if(!experiment) return <AdminDataState loading={query.isPending} error={query.error} retry={()=>void query.refetch()} />;
  const idle=["PAUSED","COMPLETED"].includes(experiment.status);
  return <div className="lab-workspace"><Link to="/admin/lab" className="lab-small inline-flex items-center gap-2 mb-5"><ArrowLeft size={14} />返回实验室</Link>
    <header className="lab-heading"><div><div className="lab-eyebrow">EXPERIMENT / {id.slice(0,8)}</div><h1>{experiment.name}</h1><p>独立经验迭代 · {experiment.playerCount} 席 · 内置 {experiment.wordPack} 词库</p></div><FlaskConical size={32} className="text-emerald-700/60" /></header>
    <div className="lab-actions mb-5">
      {["DRAFT","PAUSED"].includes(experiment.status)&&<Button size="sm" disabled={controls.isPending} onClick={()=>controls.mutate(experiment.status==="DRAFT"?"start":"resume")}><Play size={14} className="mr-2" />{experiment.status==="DRAFT"?"启动实验":"恢复实验"}</Button>}
      {experiment.status==="RUNNING"&&<Button size="sm" variant="outline" disabled={controls.isPending} onClick={()=>controls.mutate("pause")}><Pause size={14} className="mr-2" />本局后暂停</Button>}
      {!["COMPLETED","STOPPED","STOPPING"].includes(experiment.status)&&<Button size="sm" variant="outline" disabled={controls.isPending} onClick={()=>controls.mutate("stop")}><Square size={13} className="mr-2" />本局后停止</Button>}
      {experiment.status==="PAUSED"&&experiment.reviewFailed&&<Button size="sm" variant="outline" disabled={controls.isPending} onClick={()=>controls.mutate("retry-review")}>重试复盘并继续</Button>}
      <Button size="sm" variant="ghost" asChild><Link to={"/admin/lab?from="+id}>复制并继承经验</Link></Button>
      <Button size="sm" variant="ghost" asChild><a href={"/lab/"+id} target="_blank" rel="noreferrer">玩家入口</a></Button>
    </div>
    <LabProgress experiment={experiment} />
    {experiment.reason&&<div className="lab-error" role="status">{experiment.reason} · 已保留已完成对局和原请求账务，请处理后恢复。</div>}
    <div className="lab-columns mt-7"><div><LabLiveMatches matches={experiment.activeMatches??[]} />
      <section className="lab-section"><h2>冻结阵容</h2><div className="lab-scroll"><table className="lab-history"><thead><tr><th>AI 档案</th><th>模型</th><th>当前经验</th></tr></thead><tbody>{roster.map(agent=><tr key={agent.agentId}><td>{agent.name}</td><td className="break-all">{agent.modelSelection.modelKey}<div className="lab-small">{agent.modelSelection.thinkingMode} {agent.modelSelection.thinkingEffort}</div></td><td className="lab-number">v{configuration?.experiences[agent.agentId]?.sequenceNumber??0}</td></tr>)}</tbody></table></div></section>
    </div><LabCostPanel cost={experiment.cost} limit={experiment.budgetPoints} /></div>
    <section className="lab-section"><h2><GitCompareArrows size={17} />经验版本与比较</h2>
      {versions.data?<><ExperienceComparison versions={versions.data.items} roster={roster} heads={configuration?.experiences??{}} disabled={!idle||rollback.isPending} rollback={version=>rollback.mutate(version)} /><LabPager page={experiencePage} total={versions.data.total} size={30} change={setExperiencePage} /></>:<AdminDataState loading={versions.isPending} error={versions.error} retry={()=>void versions.refetch()} />}
    </section>
    <section className="lab-section"><h2>发起对照评测</h2><p className="lab-small mb-4">训练完成或暂停后，选一个 AI 与经验版本。固定初始对手，评测不复盘、不写入经验，费用仍计入本实验。</p>
      <form className="lab-form-grid" onSubmit={event=>{event.preventDefault();evaluate.mutate();}}>
        <label className="lab-field">评测对象<select value={target} onChange={event=>{setAgent(event.target.value);setCandidate("");}}>{roster.map(agent=><option key={agent.agentId} value={agent.agentId}>{agent.name}</option>)}</select></label>
        <label className="lab-field">训练后经验版本<select value={targetVersion} onChange={event=>setCandidate(event.target.value)}><option value="">选择经验</option>
          {targetVersion&&!versions.data?.items.some(version=>version.id===targetVersion)&&<option value={targetVersion}>{targetVersion==="initial"?"尚无训练经验":"当前生效版本"}</option>}
          {versions.data?.items.filter(version=>version.agentId===target).map(version=><option value={version.id} key={version.id}>v{version.sequenceNumber}</option>)}</select></label>
        <label className="lab-field">成对样本数<input type="number" min={1} max={1000} value={pairs} onChange={event=>setPairs(Number(event.target.value))} /></label>
        <Button type="submit" className="self-end" disabled={!idle||!targetVersion||targetVersion==="initial"||evaluate.isPending}>启动对照评测</Button>
      </form><LabEvaluationResults evaluations={experiment.evaluations??[]} />
    </section>
    <section className="lab-section"><h2>对局历史</h2>{history.data?<><LabMatchHistory page={history.data} /><LabPager page={page} total={history.data.total} size={20} change={setPage} /></>:<AdminDataState loading={history.isPending} error={history.error} retry={()=>void history.refetch()} />}</section>
  </div>;
}
export default function LabAdmin() { const {id}=useParams(); return id?<AdminLabDetail key={id} id={id}/>:<AdminLabIndex/>; }
