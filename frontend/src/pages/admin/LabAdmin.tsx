import { useEffect, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FlaskConical, Plus, ArrowLeft, Play, Pause, Square, RefreshCcw, GitCompareArrows } from "lucide-react";
import { toast } from "sonner";
import { adminApi, adminLabApi, personaApi, getApiErrorMessage } from "@/services/api";
import { Button } from "@/components/ui/button";
import { BrandLogo } from "@/components/brand/BrandLogo";
import { AdminDataState } from "@/components/admin/AdminDataState";
import { LabExperimentRows, LabCostPanel, LabProgress, LabLiveMatches, LabMatchHistory, LabEvaluationResults, LabPager } from "@/components/lab/LabPanels";
import { ModelSelectionFields } from "@/components/lab/ModelSelectionFields";
import { LabScenarioEvaluation } from "@/components/lab/LabScenarioEvaluation";
import { LabStrategies } from "@/components/lab/LabStrategies";
import { LabDiagnostics } from "@/components/lab/LabDiagnostics";
import { LabWerewolfTraining } from "@/components/lab/LabWerewolfTraining";
import { failureText } from "@/components/lab/labFailures";
import type { CatalogModel } from "@/types/aiModelConfig";
import type { LabAgent, LabSelection, LabExperience, LabExperiment, CreateLabExperiment, LabParticipantInput, LabGameId } from "@/types/lab";

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
  const [nameOverride,setName]=useState<string>();
  const [selectedGame,setGame]=useState<LabGameId>();
  const [initialStrategy,setInitialStrategy]=useState<string>();
  const [selected,setSelected]=useState<LabParticipantInput[]>();
  const [selectedCount,setPlayerCount]=useState<number>();
  const [selectedTargetGames,setTargetGames]=useState<number>();
  const [wordPack,setWordPack]=useState("daily");
  const [hasBlank,setHasBlank]=useState(false);
  const [budgetOverride,setBudget]=useState<string>();
  const [coach,setCoach]=useState<LabSelection>(emptySelection);
  const [allModel,setAllModel]=useState("");
  const [sourceId,setSourceId]=useState(from);
  const [inheritId,setInheritId]=useState(from);
  const list=useQuery({queryKey:["admin","lab","list",page],queryFn:()=>adminLabApi.list(page),refetchInterval:5000});
  const agents=useQuery({queryKey:["admin","lab","agents"],queryFn:adminLabApi.agents});
  const catalog=useQuery({queryKey:["admin","model-config"],queryFn:adminApi.modelConfiguration});
  const source=useQuery({queryKey:["admin","lab","source",inheritId],queryFn:()=>adminLabApi.detail(inheritId),enabled:!!inheritId});
  const gameId=selectedGame??(from?source.data?.gameId??"undercover":"undercover");
  const werewolf=gameId==="werewolf";
  const name=nameOverride??(werewolf?"狼人杀共享策略实验":"卧底策略实验");
  const targetGames=selectedTargetGames??(werewolf?2:100);
  const budget=budgetOverride??(werewolf?"30.00":"10.00");
  const sourceStrategies=useQuery({queryKey:["admin","lab","strategies",inheritId],queryFn:()=>adminLabApi.strategies(inheritId),enabled:!!inheritId&&source.data?.gameId==="werewolf"});
  const initialStrategyVersionId=initialStrategy??(source.data?.gameId===gameId?sourceStrategies.data?.strategyHeadId:undefined);
  const playerCount=selectedCount??(from?source.data?.playerCount??6:6);
  const chosen=selected??(from?source.data?.configuration?.roster.map(agent=>({agentId:agent.agentId,selection:agent.modelSelection,initialExperienceId:source.data?.configuration?.experiences?.[agent.participantId]?.experienceId}))??[]:[]);
  const changeSlot=(index:number,value:LabParticipantInput)=>setSelected(Array.from({length:playerCount},(_,seat)=>seat===index?value:chosen[seat]??{agentId:""}));
  const sync=useMutation({mutationFn:adminApi.syncModelConfiguration,onSuccess:data=>{client.setQueryData(["admin","model-config"],data);toast.success("模型目录已同步");},onError:failure});
  const create=useMutation({
    mutationFn:async()=>{
      const configured=coach.modelKey?coach:{...coach,modelKey:catalog.data?.functions.find(config=>config.functionCode===(werewolf?"WEREWOLF":"UNDERCOVER"))?.modelKey??catalog.data?.models[0]?.modelKey??""};
      const request:CreateLabExperiment={name,gameId,participants:chosen.slice(0,playerCount).map(slot=>({...slot,selection:slot.selection??agents.data?.find(agent=>agent.id===slot.agentId)?.modelSelection,initialExperienceId:werewolf||slot.initialExperienceId==="initial"?undefined:slot.initialExperienceId})),playerCount,targetGames,
        ...(werewolf?{learningMode:"SHARED_STRATEGY" as const,initialStrategyVersionId:initialStrategyVersionId||undefined,rules:{template:"standard" as const,playerCount:playerCount as 6|9,witchRule:"first_night" as const,winCondition:"side" as const,speechTime:60,hasLastWords:"first_night" as const}}:{wordPack,hasBlank}),
        budgetPoints:Math.round(Number(budget)*100),coach:configured};
      return adminLabApi.create(request);
    },
    onSuccess:experiment=>{void client.invalidateQueries({queryKey:["admin","lab"]});navigate("/admin/lab/"+experiment.id);},onError:failure,
  });
  const models=catalog.data?.models??[];
  const newExperiment=()=>{setCreating(true);setGame("undercover");setInitialStrategy("");setName("卧底策略实验");setTargetGames(100);setBudget("10.00");setPlayerCount(6);setInheritId("");setSourceId("");setSelected(Array.from({length:6},(_,index)=>({agentId:agents.data?.[index%(agents.data?.length||1)]?.id??""})));setCoach({...emptySelection,modelKey:catalog.data?.functions.find(config=>config.functionCode==="UNDERCOVER")?.modelKey??models[0]?.modelKey??""});};
  const changeGame=(next:LabGameId)=>{
    const count=next==="werewolf"?9:6;setGame(next);setPlayerCount(count);setName(next==="werewolf"?"狼人杀共享策略实验":"卧底策略实验");setTargetGames(next==="werewolf"?2:100);setBudget(next==="werewolf"?"30.00":"10.00");
    setInheritId("");setSourceId("");setInitialStrategy("");setSelected(Array.from({length:count},(_,index)=>({agentId:chosen[index]?.agentId??agents.data?.[index%(agents.data?.length||1)]?.id??"",selection:chosen[index]?.selection})));
  };
  return <div className="lab-workspace">
    <header className="lab-heading"><div><div className="lab-eyebrow"><BrandLogo compact /> / RESEARCH CONTROL</div><h1>AI 实验室</h1><p>以完整对局积累经验，用固定对手检验变化。</p></div>
      <div className="lab-actions"><Button variant="outline" size="sm" onClick={()=>sync.mutate()} disabled={sync.isPending}><RefreshCcw size={14} className="mr-2" />同步模型</Button><Button size="sm" onClick={newExperiment}><Plus size={14} className="mr-2" />新建实验</Button></div></header>
    <div className="lab-tabs" role="tablist"><button role="tab" aria-selected={tab==="experiments"} onClick={()=>setTab("experiments")}>实验任务</button><button role="tab" aria-selected={tab==="agents"} onClick={()=>setTab("agents")}>独立 AI 档案 · {agents.data?.length??0}</button></div>
    {catalog.error&&<div className="lab-error" role="alert">{getApiErrorMessage(catalog.error,"模型目录读取失败")}</div>}
    {!models.length&&<p className="lab-note">请先同步模型目录，再创建独立 AI 档案。目录读取不会产生推理费用。</p>}
    {creating&&<form className="lab-form space-y-5" onSubmit={(event:FormEvent)=>{event.preventDefault();create.mutate();}}><h2 className="font-semibold">{werewolf?"新建狼人杀实验":"新建卧底实验"}</h2>
      <label className="lab-field">训练玩法<select aria-label="训练玩法" value={gameId} onChange={event=>changeGame(event.target.value as LabGameId)}><option value="undercover">谁是卧底</option><option value="werewolf">狼人杀</option></select></label>
      <div className="lab-form-grid"><label className="lab-field">实验名称<input value={name} maxLength={100} required onChange={event=>setName(event.target.value)} /></label>
        <label className="lab-field">{werewolf?"训练板子":"席位数"}<select aria-label="训练席位数" value={playerCount} onChange={event=>{const count=Number(event.target.value);setSelected(Array.from({length:count},(_,index)=>chosen[index]??{agentId:agents.data?.[0]?.id??""}));setPlayerCount(count);}}>{(werewolf?[9,6]:[4,5,6,7,8,9,10]).map(count=><option key={count} value={count}>{werewolf?`${count} 人标准 · ${count===9?"主要训练":"快速检查"}`:count}</option>)}</select></label>
        {(!werewolf||playerCount!==9)&&<label className="lab-field">目标对局数<input type="number" min={1} max={10000} value={targetGames} onChange={event=>setTargetGames(Number(event.target.value))} required /></label>}
        {!werewolf&&<label className="lab-field">内置词库<select value={wordPack} onChange={event=>setWordPack(event.target.value)}><option value="daily">日常生活</option><option value="idiom">成语俗语</option><option value="acg">二次元</option><option value="tech">硬核科技</option></select></label>}
        <label className="lab-field">费用预算（人民币）<input type="number" min="0.01" step="0.01" value={budget} required onChange={event=>setBudget(event.target.value)} /></label>
        {!werewolf&&<label className="lab-field"><input type="checkbox" checked={hasBlank} onChange={event=>setHasBlank(event.target.checked)} />加入白板玩家</label>}</div>
      {werewolf&&<p className="lab-note">共享策略：通用、狼人、村民、预言家、女巫、猎人分层积累；人格风格与策略分开。全体席位须使用相同模型与思考档位，人格可以重复。标准板、屠边、首夜可自救、首夜遗言，发言 60 秒。6 人板不含猎人。</p>}
      {werewolf&&playerCount===9&&<p className="lab-small">创建后进入“规则与证据”训练场：8 个开发案例 → 策略修改与回测 → 32 对独立保留题 → 2 对完整对局。先试跑一个代表案例，再检查并继续；创建本身不会调用模型。</p>}
      <div><h3 className="text-sm font-medium">实验阵容 · {playerCount} 个独立副本</h3><p className="lab-small my-3">{werewolf?"模型只用于本次实验；允许重复人格，所有席位共同积累策略，各自保留当局可见信息。":"模型只用于本次实验，不修改来源档案。同角色副本可使用不同模型，各自独立学习。"}</p>
        <div className="lab-bulk-model"><label className="lab-field">统一玩家模型<select aria-label="统一玩家模型" value={allModel} onChange={event=>setAllModel(event.target.value)}><option value="">选择统一模型</option>{models.map(model=><option key={model.modelKey} value={model.modelKey}>{model.displayName}</option>)}</select></label>
          <Button type="button" variant="outline" disabled={!models.some(model=>model.modelKey===allModel)} onClick={()=>{setSelected(Array.from({length:playerCount},(_,index)=>({...chosen[index],agentId:chosen[index]?.agentId??"",selection:{modelKey:allModel,thinkingMode:"DEFAULT",thinkingEffort:null}})));toast.success("全部玩家模型已设置，思考配置重置为服务默认；复盘教练不变");}}>应用到全部 AI 玩家</Button><p className="lab-small">不影响复盘教练；可在下面逐个调整。</p></div>
        <div className="space-y-3">{Array.from({length:playerCount},(_,index)=>{
        const slot=chosen[index]??{agentId:""};
        const sources=source.data?.configuration?.roster.filter(profile=>profile.agentId===slot.agentId)??[];
        return <div key={index} className="lab-table space-y-3"><label className="lab-field">副本 {index+1}<select aria-label={`副本 ${index+1} 的 AI 档案`} value={slot.agentId} required onChange={event=>changeSlot(index,{agentId:event.target.value,selection:agents.data?.find(agent=>agent.id===event.target.value)?.modelSelection})}><option value="">选择 AI 档案</option>{agents.data?.map(agent=><option key={agent.id} value={agent.id}>{agent.name}</option>)}</select></label>
          <ModelSelectionFields models={models} value={slot.selection??agents.data?.find(agent=>agent.id===slot.agentId)?.modelSelection??emptySelection} change={selection=>changeSlot(index,{...slot,selection})} prefix={`participant-${index+1}`} />
          {!!source.data&&!werewolf&&source.data.gameId!=="werewolf"&&<label className="lab-field">继承经验<select aria-label={`副本 ${index+1} 的继承经验`} value={slot.initialExperienceId??""} onChange={event=>changeSlot(index,{...slot,initialExperienceId:event.target.value||undefined})}><option value="">从空经验开始</option>{sources.map(profile=>{const head=source.data?.configuration?.experiences?.[profile.participantId];return head&&head.experienceId!=="initial"?<option key={profile.participantId} value={head.experienceId}>{profile.name} · v{head.sequenceNumber}</option>:null;})}</select></label>}
        </div>;
      })}</div>
        {!agents.data?.length&&<p className="lab-small mt-2">先到“独立 AI 档案”创建阵容。</p>}</div>
      <div><h3 className="text-sm font-medium mb-3">统一复盘教练</h3><ModelSelectionFields models={models} value={coach.modelKey?coach:{...coach,modelKey:catalog.data?.functions.find(config=>config.functionCode===(werewolf?"WEREWOLF":"UNDERCOVER"))?.modelKey??models[0]?.modelKey??""}} change={setCoach} prefix="coach" /></div>
      <div className="lab-form-grid"><label className="lab-field">{werewolf?"共享策略来源实验 ID（可选）":"继承经验来源实验 ID（可选）"}<input value={sourceId} onChange={event=>setSourceId(event.target.value)} placeholder="空白表示从初始经验开始" /></label><Button variant="outline" className="self-end" type="button" onClick={()=>{setInheritId(sourceId.trim());setInitialStrategy(undefined);}}>读取来源经验</Button></div>
      {source.data&&<p className="lab-small">来源：“{source.data.name}”。{werewolf?"选定共享策略作为起点，空策略表示重新开始。":"逐副本选择继承经验；复制实验保留来源模型，可逐席调整，人格从当前档案复制。"}</p>}
      {werewolf&&<label className="lab-field">初始共享策略<select aria-label="初始共享策略" value={initialStrategyVersionId??""} onChange={event=>setInitialStrategy(event.target.value)}><option value="">从空策略开始</option>{source.data?.gameId==="werewolf"&&sourceStrategies.data?.items.map(version=><option key={version.id} value={version.id}>v{version.sequenceNumber}</option>)}</select></label>}
      {source.data&&((source.data.gameId??"undercover")!==gameId)&&<p className="lab-error" role="alert">来源实验玩法不同，无法继承经验。</p>}
      {sourceStrategies.error&&<p className="lab-error" role="alert">共享策略读取失败，请重试读取来源经验。</p>}
      {source.error&&<p className="lab-error" role="alert">{getApiErrorMessage(source.error,"来源实验读取失败")}</p>}
      <p className="lab-note">创建后为草稿，需要手动启动。对局、修正、复盘与评测共享实耗预算，在途结算可能超过预算；实验对局不计普通排名、成就和奖励。</p>
      {create.error&&<p className="lab-error" role="alert">{getApiErrorMessage(create.error,"创建失败")}</p>}
      <div className="lab-actions"><Button type="submit" disabled={create.isPending||!!source.error||!!sourceStrategies.error||(!!inheritId&&source.isPending)||(werewolf&&!!inheritId&&sourceStrategies.isPending)||chosen.length<playerCount||chosen.slice(0,playerCount).some(slot=>!slot.agentId)||!models.length}>创建实验草稿</Button><Button type="button" variant="ghost" onClick={()=>setCreating(false)}>取消</Button></div>
    </form>}
    {tab==="agents"?<AgentEditor models={models} agents={agents.data??[]} reload={()=>void agents.refetch()} />:
      list.data?<><LabExperimentRows rows={list.data.items} admin /><LabPager page={page} total={list.data.total} size={20} change={setPage} /></>:<AdminDataState loading={list.isPending} error={list.error} retry={()=>void list.refetch()} />}
  </div>;
}

function ExperienceComparison({versions,roster,heads,rollback,disabled}:{versions:LabExperience[];roster:{participantId:string;name:string}[];heads:Record<string,{experienceId:string}>;rollback:(id:string)=>void;disabled:boolean}) {
  const [selectedAgent,setAgent]=useState("");
  const [before,setBefore]=useState("");
  const [after,setAfter]=useState("");
  const agentId=selectedAgent||roster[0]?.participantId||"";
  const entries=versions.filter(version=>version.participantId===agentId);
  const earlier=entries.find(version=>version.id===before)||entries[1];
  const later=entries.find(version=>version.id===after)||entries[0];
  return <div><div className="lab-form-grid"><label className="lab-field">AI 副本<select value={agentId} onChange={event=>{setAgent(event.target.value);setBefore("");setAfter("");}}>{roster.map(agent=><option key={agent.participantId} value={agent.participantId}>{agent.name}</option>)}</select></label><p className="lab-small self-end">每个版本保留来源对局与证据事件。回退生成新版本，仅影响之后开局。</p></div>
    <div className="lab-compare">{[{title:"较早版本",value:earlier,choose:setBefore},{title:"比较版本",value:later,choose:setAfter}].map(column=><section key={column.title}><label className="lab-field">{column.title}<select value={column.value?.id??""} onChange={event=>column.choose(event.target.value)}><option value="">无版本</option>{entries.map(version=><option key={version.id} value={version.id}>v{version.sequenceNumber}{heads[agentId]?.experienceId===version.id?" · 当前":""} · {version.createdAt.replace("T"," ").slice(0,16)}</option>)}</select></label>
      <div className="lab-memory mt-3">{column.value?.summary||"当前页没有可比较的经验。"}</div>
      {column.value&&<div className="lab-actions mt-3">{column.value.evidence.archiveId&&<Button asChild size="sm" variant="outline"><Link to={"/admin/replays/"+column.value.evidence.archiveId}>来源回放</Link></Button>}<Button size="sm" variant="outline" disabled={disabled} onClick={()=>rollback(column.value!.id)}>回退到此经验</Button></div>}</section>)}</div>
    {later&&<p className="lab-small">证据事件：{later.evidence.eventIds?.join(" · ")||"管理员回退"}{earlier?.summary===later.summary?" · 两版内容相同":""}</p>}
  </div>;
}
function ExperimentBudget({experiment,saved}:{experiment:LabExperiment;saved:(value:LabExperiment)=>void}) {
  const [editing,setEditing]=useState(false),[amount,setAmount]=useState("");
  const update=useMutation({mutationFn:(points:number)=>adminLabApi.updateBudget(experiment.id,points),onSuccess:value=>{
    saved(value);setEditing(false);toast.success("预算已保存；已暂停的实验需手动继续");
  }});
  const [validation,setValidation]=useState("");
  const submit=(event:FormEvent)=>{
    event.preventDefault();
    const parts=/^(\d+)(?:\.(\d{1,2}))?$/.exec(amount.trim());
    const points=parts?Number(parts[1])*100+Number((parts[2]??"").padEnd(2,"0")):NaN;
    if(!Number.isSafeInteger(points)||points<Math.max(1,experiment.cost.spentPoints)) {setValidation("预算须为正数，且不能低于当前已扣积分");return;}
    setValidation("");update.mutate(points);
  };
  return <section className="lab-section" aria-label="预算调整"><h2>实验预算</h2>
    <p className="lab-small mb-3">当前 ¥{(experiment.budgetPoints/100).toFixed(2)} · 已扣 {experiment.cost.spentPoints} 积分。运行中也可修改，保存后影响后续调用。</p>
    {editing?<form className="lab-form-grid" onSubmit={submit}>
      <label className="lab-field">费用预算（人民币）<input aria-label="修改费用预算" type="number" step="0.01" min={(Math.max(1,experiment.cost.spentPoints)/100).toFixed(2)} required value={amount} onChange={event=>setAmount(event.target.value)}/></label>
      <div className="lab-actions self-end"><Button type="submit" disabled={update.isPending}>保存预算</Button><Button type="button" variant="ghost" disabled={update.isPending} onClick={()=>setEditing(false)}>取消</Button></div>
      {(validation||update.error)&&<p className="lab-error" role="alert">{validation||getApiErrorMessage(update.error,"预算保存失败，编辑内容已保留")}</p>}
    </form>:<Button size="sm" variant="outline" onClick={()=>{setAmount((experiment.budgetPoints/100).toFixed(2));setValidation("");update.reset();setEditing(true);}}>修改预算</Button>}
  </section>;
}
function AdminLabDetail({id}:{id:string}) {
  const client=useQueryClient();
  const [section,setSection]=useState("runtime");
  const [inspectedMatch,setInspectedMatch]=useState("");
  const [page,setPage]=useState(0),[experiencePage,setExperiencePage]=useState(0);
  const [agentId,setAgent]=useState(""),[candidate,setCandidate]=useState(""),[pairs,setPairs]=useState(20);
  const [targetRole,setTargetRole]=useState("");
  const evaluationKey=`lab-strategy-evaluation:${id}`;
  const [pendingEvaluation,setPendingEvaluation]=useState<{participantId:string;candidateStrategyVersionId:string;pairs:number;targetRole?:string;operationId:string}>(()=>{
    try {const value=JSON.parse(localStorage.getItem(evaluationKey)??"null");return value&&typeof value.operationId==="string"?value:undefined;} catch {return undefined;}
  });
  const query=useQuery({queryKey:["admin","lab","experiment",id],queryFn:()=>adminLabApi.detail(id),refetchInterval:2000});
  const werewolf=query.data?.gameId==="werewolf";
  const strategies=useQuery({queryKey:["admin","lab","strategies",id],queryFn:()=>adminLabApi.strategies(id),enabled:werewolf,refetchInterval:5000});
  const history=useQuery({queryKey:["admin","lab","matches",id,page],queryFn:()=>adminLabApi.matches(id,page),refetchInterval:5000});
  const versions=useQuery({queryKey:["admin","lab","experiences",id,experiencePage],queryFn:()=>adminLabApi.experiences(id,experiencePage),enabled:!!query.data&&!werewolf,refetchInterval:5000});
  const refresh=()=>void client.invalidateQueries({queryKey:["admin","lab"]});
  const controls=useMutation({mutationFn:(action:string)=>action==="retry-review"?adminLabApi.retryReview(id):adminLabApi.control(id,action),onSuccess:refresh,onError:failure});
  const rollback=useMutation({mutationFn:(version:string)=>adminLabApi.rollback(id,version),onSuccess:()=>{refresh();toast.success("回退已保存为新版本");},onError:failure});
  const experiment=query.data;
  const configuration=experiment?.configuration;
  const activeScenario=configuration?.activeScenario?configuration.scenarioRuns?.[configuration.activeScenario]:undefined;
  const roster=configuration?.roster??[];
  const target=agentId||roster[0]?.participantId||"";
  const targetVersion=candidate||(werewolf?strategies.data?.strategyHeadId:configuration?.experiences?.[target]?.experienceId)||"";
  const evaluate=useMutation({mutationFn:async()=>{
    if(!werewolf) return adminLabApi.evaluate(id,target,targetVersion,pairs);
    const request=pendingEvaluation??{participantId:target,candidateStrategyVersionId:targetVersion,pairs,targetRole:targetRole||undefined,operationId:crypto.randomUUID()};
    localStorage.setItem(evaluationKey,JSON.stringify(request));setPendingEvaluation(request);
    return adminLabApi.evaluateStrategy(id,request.participantId,request.candidateStrategyVersionId,request.pairs,{targetRole:request.targetRole,operationId:request.operationId});
  },onSuccess:()=>{localStorage.removeItem(evaluationKey);setPendingEvaluation(undefined);refresh();},onError:failure});
  useEffect(()=>{
    if(pendingEvaluation&&experiment?.evaluations?.some(evaluation=>evaluation.operationId===pendingEvaluation.operationId)) {
      localStorage.removeItem(evaluationKey);setPendingEvaluation(undefined);
    }
  },[pendingEvaluation,experiment?.evaluations,evaluationKey]);
  if(!experiment) return <AdminDataState loading={query.isPending} error={query.error} retry={()=>void query.refetch()} />;
  if(werewolf&&experiment.playerCount===9&&experiment.trainingRuns!==undefined) return <LabWerewolfTraining experiment={experiment} strategies={strategies.data} reload={refresh}
    budgetEditor={<ExperimentBudget experiment={experiment} saved={value=>{client.setQueryData(["admin","lab","experiment",id],value);refresh();}}/>}
    history={<><LabMatchHistory page={history.data??{items:[],page:0,total:0}} admin onInspect={setInspectedMatch}/><LabPager page={page} total={history.data?.total??0} size={20} change={setPage}/>{(inspectedMatch||experiment.latestFailureMatchId)&&<LabDiagnostics experimentId={id} matchId={inspectedMatch||experiment.latestFailureMatchId!}/>}</>}/>
  const idle=["PAUSED","COMPLETED"].includes(experiment.status);
  return <div className="lab-workspace"><Link to="/admin/lab" className="lab-small inline-flex items-center gap-2 mb-5"><ArrowLeft size={14} />返回实验室</Link>
    <header className="lab-heading"><div><div className="lab-eyebrow">EXPERIMENT / {id.slice(0,8)}</div><h1>{experiment.name}</h1><p>{werewolf?"狼人杀 · 共享策略训练":"独立经验迭代"} · {experiment.playerCount} 席{werewolf?" · 标准板 · 效果待验证":` · 内置 ${experiment.wordPack} 词库`}</p></div><FlaskConical size={32} className="text-emerald-700/60" /></header>
    <div className="lab-actions mb-5">
      {["DRAFT","PAUSED"].includes(experiment.status)&&!experiment.reviewFailed&&activeScenario?.status!=="RUNNING"&&<Button size="sm" disabled={controls.isPending} onClick={()=>controls.mutate(experiment.status==="DRAFT"?"start":"resume")}><Play size={14} className="mr-2" />{experiment.status==="DRAFT"?"启动实验":activeScenario?.status==="PAUSED"?"继续情境评测":experiment.activeMatches?.some(match=>match.status==="PLAYING")?"继续当前局":"继续实验"}</Button>}
      {(experiment.status==="RUNNING"||activeScenario?.status==="RUNNING")&&<Button size="sm" variant="outline" disabled={controls.isPending} onClick={()=>controls.mutate("pause")}><Pause size={14} className="mr-2" />{activeScenario?"暂停情境评测":"本局后暂停"}</Button>}
      {!["COMPLETED","STOPPED","STOPPING"].includes(experiment.status)&&<Button size="sm" variant="outline" disabled={controls.isPending} onClick={()=>controls.mutate("stop")}><Square size={13} className="mr-2" />{activeScenario?"结束情境评测":experiment.budgetPaused?"结束实验":"本局后停止"}</Button>}
      {experiment.status==="PAUSED"&&experiment.reviewFailed&&<Button size="sm" variant="outline" disabled={controls.isPending} onClick={()=>controls.mutate("retry-review")}>重试复盘并继续</Button>}
      <Button size="sm" variant="ghost" asChild><Link to={"/admin/lab?from="+id}>复制并继承经验</Link></Button>
      <Button size="sm" variant="ghost" asChild><a href={"/lab/"+id} target="_blank" rel="noreferrer">玩家入口</a></Button>
    </div>
    <LabProgress experiment={experiment} />
    {experiment.reason==="TRAINING_CHECKPOINT_REACHED"&&<p className="lab-note" role="status">已达到训练费用检查线，当前局及复盘已保存。可先检查共享策略并进入评测；这不是对局故障，后续调用仍受实验实扣预算控制。</p>}
    {(inspectedMatch||experiment.latestFailureMatchId)?<LabDiagnostics experimentId={id} matchId={inspectedMatch||experiment.latestFailureMatchId!}/>:experiment.reason&&experiment.reason!=="TRAINING_CHECKPOINT_REACHED"&&<div className="lab-error" role="status">{failureText(experiment.reason)} · 历史记录未提供具体对局，请查看对局历史。</div>}
    <div className="lab-columns mt-7"><div className="min-w-0">
      <div className="lab-tabs" role="tablist" aria-label="实验详情分类">{[["runtime","运行与历史"],["roster","阵容与模型"],["experience",werewolf?"共享策略":"经验"],["evaluation","评测"]].map(([value,label])=><button role="tab" aria-selected={section===value} key={value} onClick={()=>setSection(value)}>{label}</button>)}</div>
      {section==="runtime"&&<><LabLiveMatches gameId={experiment.gameId} matches={experiment.activeMatches??[]} adminExperimentId={id} />
        <section className="lab-section"><h2>对局历史</h2>{history.data?<><LabMatchHistory page={history.data} admin onInspect={matchId=>setInspectedMatch(matchId)}/><LabPager page={page} total={history.data.total} size={20} change={setPage} /></>:<AdminDataState loading={history.isPending} error={history.error} retry={()=>void history.refetch()} />}</section></>}
      {section==="roster"&&<section className="lab-section"><h2>冻结阵容</h2><p className="lab-small mb-3">{werewolf?"此配置仅用于本实验；每局随机座次与身份，共同积累分身份策略。":"此配置仅用于本实验；每局随机座次，各副本独立学习。"}</p><div className="lab-scroll"><table className="lab-history"><thead><tr><th>AI 副本</th><th>模型</th><th>当前经验</th></tr></thead><tbody>{roster.map(agent=><tr key={agent.participantId}><td>{agent.name}</td><td className="break-all">{agent.modelSelection.modelKey}<div className="lab-small">{agent.modelSelection.thinkingMode} {agent.modelSelection.thinkingEffort}</div></td><td className="lab-number">{werewolf?`共享 v${strategies.data?.items.find(version=>version.id===strategies.data.strategyHeadId)?.sequenceNumber??0}`:`v${configuration?.experiences?.[agent.participantId]?.sequenceNumber??0}`}</td></tr>)}</tbody></table></div><p className="lab-small mt-3">复盘教练：{configuration?.coach.modelKey} · {configuration?.coach.thinkingMode} {configuration?.coach.thinkingEffort}</p></section>}
    {section==="experience"&&werewolf&&(strategies.data?<LabStrategies experimentId={id} data={strategies.data} reload={refresh}/>:<AdminDataState loading={strategies.isPending} error={strategies.error} retry={()=>void strategies.refetch()}/>)}
    {section==="experience"&&!werewolf&&<section className="lab-section"><h2><GitCompareArrows size={17} />经验版本与比较</h2>
      {versions.data?<><ExperienceComparison versions={versions.data.items} roster={roster} heads={configuration?.experiences??{}} disabled={!idle||rollback.isPending} rollback={version=>rollback.mutate(version)} /><LabPager page={experiencePage} total={versions.data.total} size={30} change={setExperiencePage} /></>:<AdminDataState loading={versions.isPending} error={versions.error} retry={()=>void versions.refetch()} />}
    </section>}
    {section==="evaluation"&&<section className="lab-section"><h2>发起对照评测</h2><p className="lab-small mb-4">训练完成或暂停后，选一个 AI 与经验版本。固定初始对手，评测不复盘、不写入经验，费用仍计入本实验。</p>
      <form className="lab-form-grid" onSubmit={event=>{event.preventDefault();evaluate.mutate();}}>
        <label className="lab-field">评测对象<select disabled={!!pendingEvaluation} value={target} onChange={event=>{setAgent(event.target.value);setCandidate("");}}>{roster.map(agent=><option key={agent.participantId} value={agent.participantId}>{agent.name}</option>)}</select></label>
        <label className="lab-field">{werewolf?"候选共享策略版本":"训练后经验版本"}<select aria-label="评测候选版本" disabled={!!pendingEvaluation} value={targetVersion} onChange={event=>setCandidate(event.target.value)}><option value="">选择经验</option>
          {!werewolf&&targetVersion&&!versions.data?.items.some(version=>version.id===targetVersion)&&<option value={targetVersion}>{targetVersion==="initial"?"尚无训练经验":"当前生效版本"}</option>}
          {werewolf?strategies.data?.items.map(version=><option value={version.id} key={version.id}>v{version.sequenceNumber}</option>):versions.data?.items.filter(version=>version.participantId===target).map(version=><option value={version.id} key={version.id}>v{version.sequenceNumber}</option>)}</select></label>
        {werewolf&&<label className="lab-field">目标游戏身份<select aria-label="目标游戏身份" disabled={!!pendingEvaluation} value={targetRole} onChange={event=>setTargetRole(event.target.value)}><option value="">随机身份</option>{["WEREWOLF","VILLAGER","SEER","WITCH",...(experiment.playerCount===9?["HUNTER"]:[])].map(role=><option key={role} value={role}>{{WEREWOLF:"狼人",VILLAGER:"村民",SEER:"预言家",WITCH:"女巫",HUNTER:"猎人"}[role]}</option>)}</select></label>}
        <label className="lab-field">成对样本数<input type="number" min={1} max={1000} disabled={!!pendingEvaluation} value={pairs} onChange={event=>setPairs(Number(event.target.value))} /></label>
        <Button type="submit" className="self-end" disabled={!idle||!!activeScenario||!targetVersion||targetVersion==="initial"||evaluate.isPending||!!pendingEvaluation}>启动对照评测</Button>
      </form>{pendingEvaluation&&<div className="lab-note mt-3" role="status">评测响应待确认 · {pendingEvaluation.operationId}<div className="lab-actions mt-2"><Button variant="outline" size="sm" onClick={()=>void query.refetch()}>查询原评测</Button><Button variant="outline" size="sm" disabled={evaluate.isPending} onClick={()=>evaluate.mutate()}>使用原评测请求重试</Button></div></div>}
      <LabEvaluationResults gameId={experiment.gameId} evaluations={experiment.evaluations??[]} />
      {werewolf&&(experiment.playerCount===9?<LabScenarioEvaluation experimentId={id} candidateVersionId={targetVersion} runs={configuration?.scenarioRuns??{}} caseCount={configuration?.scenarioManifest?.length??20} sourceGroupCount={new Set(configuration?.scenarioManifest?.map(item=>item.sourceGroup).filter(Boolean)).size} idle={idle}/>:<p className="lab-note">情境保留题适用于 9 人标准板；6 人快速检查可使用上方整局对照评测。</p>)}
    </section>}
    </div><div><LabCostPanel cost={experiment.cost} limit={experiment.budgetPoints} /><ExperimentBudget experiment={experiment} saved={value=>{client.setQueryData(["admin","lab","experiment",id],value);refresh();}} /></div></div>
  </div>;
}
export default function LabAdmin() { const {id}=useParams(); return id?<AdminLabDetail key={id} id={id}/>:<AdminLabIndex/>; }
