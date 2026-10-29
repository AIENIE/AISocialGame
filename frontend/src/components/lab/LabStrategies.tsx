import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { adminLabApi, personaApi, getApiErrorMessage } from "@/services/api";
import { Button } from "@/components/ui/button";
import { labText } from "@/i18n/labTexts";
import type { LabStrategyList, LabStrategyPublication, LabStrategyPublicationRequest, LabStrategyAssessment, LabStrategyAssessmentInput, LabStrategyEvidence } from "@/types/lab";

const roles=["GENERAL","WEREWOLF","VILLAGER","SEER","WITCH","HUNTER"];
const phases:Record<string,string>={NIGHT:"夜间行动",DAY_DISCUSS:"白天发言",DAY_INTERACTION:"质询",DAY_VOTE:"白天投票",DEATH_ACTION:"死亡技能",LAST_WORDS:"遗言"};
const roleName=(role:string)=>labText("zh-CN",role);
function StrategyEvidence({items,previous=false}:{items:LabStrategyEvidence[];previous?:boolean}) {
  return <>{items.map((evidence,index)=>{
    const source="caseId" in evidence?`案例 ${evidence.caseId} · 任务 ${evidence.taskId}`:`行动 ${evidence.jobId}`;
    const eventIds="evidenceIds" in evidence?evidence.evidenceIds:evidence.eventIds;
    return <p className="break-all mt-2" key={`${source}:${index}`}>{previous&&"前版"}{source} · 玩家 {evidence.actorId}<br/>事件：{eventIds.join(" · ")||"无事件引用"}</p>;
  })}</>;
}
function savedOperation(key:string):LabStrategyPublicationRequest|undefined {
  try {
    const value=JSON.parse(localStorage.getItem(key)??"null");
    if(value&&typeof value.operationId==="string"&&["PUBLISH","WITHDRAW"].includes(value.action)&&Array.isArray(value.personaIds)&&value.mode==="TRIAL") return value;
  } catch { /* An unreadable local draft cannot authorize a publication. */ }
  return undefined;
}
function responseStatus(error:unknown):number|undefined {
  if(typeof error!=="object"||error===null||!("response" in error)) return undefined;
  const response=error.response;
  return typeof response==="object"&&response!==null&&"status" in response&&typeof response.status==="number"?response.status:undefined;
}

function StrategyAssessment({experimentId,versionId,current,saved}:{experimentId:string;versionId:string;current?:LabStrategyAssessment;saved:(value:LabStrategyAssessment)=>void}) {
  const [flags,setFlags]=useState(()=>({ruleError:current?String(current.ruleError):"",evidenceLeak:current?String(current.evidenceLeak):"",severeContradiction:current?String(current.severeContradiction):""}));
  const [notes,setNotes]=useState(current?.notes??"");
  const assessment=useMutation({mutationFn:(input:LabStrategyAssessmentInput)=>adminLabApi.assessStrategy(experimentId,versionId,input),onSuccess:saved});
  return <details className="mt-4"><summary>策略人工评审 · {current?"已记录":"待评审"}</summary><form className="space-y-3 mt-3" onSubmit={event=>{event.preventDefault();if(Object.values(flags).some(value=>value===""))return;assessment.mutate({ruleError:flags.ruleError==="true",evidenceLeak:flags.evidenceLeak==="true",severeContradiction:flags.severeContradiction==="true",notes});}}>
    <p className="lab-small">依据策略与来源证据逐项判断。任一问题标为“存在”会阻止该版本发布；全部为“未发现”也不代表能力已提升。</p>
    {([["ruleError","规则错误"],["evidenceLeak","证据越界"],["severeContradiction","严重矛盾"]] as const).map(([key,label])=><label className="lab-field" key={key}>{label}<select aria-label={`评审${label}`} value={flags[key]} onChange={event=>setFlags(value=>({...value,[key]:event.target.value}))} required><option value="">待判断</option><option value="false">未发现</option><option value="true">存在</option></select></label>)}
    <label className="lab-field">评审说明<textarea aria-label="策略评审说明" maxLength={4000} value={notes} onChange={event=>setNotes(event.target.value)}/></label>
    <Button size="sm" type="submit" disabled={assessment.isPending||Object.values(flags).some(value=>value==="")}>保存策略评审</Button>
    {current&&<p className="lab-small">评审人 {current.reviewedBy} · {current.reviewedAt.replace("T"," ")}</p>}
    {assessment.error&&<p className="lab-error" role="alert">{getApiErrorMessage(assessment.error,"评审保存失败，内容已保留")}</p>}
  </form></details>;
}

export function LabStrategies({experimentId,data,reload,view="all",versionId}:{experimentId:string;data:LabStrategyList;reload:()=>void;view?:"all"|"strategy"|"publication";versionId?:string|null}) {
  const storageKey=`lab-strategy-publication:${experimentId}`;
  const [selectedId,setSelectedId]=useState("");
  const [personaIds,setPersonaIds]=useState<string[]>([]);
  const [pending,setPending]=useState(()=>savedOperation(storageKey));
  const [receipt,setReceipt]=useState<LabStrategyPublication>();
  const [message,setMessage]=useState("");
  const [notFound,setNotFound]=useState(false);
  const [savedAssessments,setSavedAssessments]=useState<Record<string,LabStrategyAssessment>>({});
  const personas=useQuery({queryKey:["admin","lab","personas"],queryFn:personaApi.list});
  const selected=versionId!==undefined?data.items.find(version=>version.id===versionId):data.items.find(version=>version.id===(selectedId||data.strategyHeadId))??data.items[0];
  const previous=data.items.find(version=>version.id===selected?.previousId);
  const assessment=selected?(savedAssessments[selected.id]??data.assessments?.[selected.id]):undefined;
  const blocked=!!assessment&&(assessment.ruleError||assessment.evidenceLeak||assessment.severeContradiction);
  const cards=selected?.content.cards??[];
  const before=new Map(previous?.content.cards.map(card=>[card.id,card])??[]);
  const added=cards.filter(card=>!before.has(card.id));
  const changed=cards.filter(card=>before.has(card.id)&&JSON.stringify(before.get(card.id))!==JSON.stringify(card));
  const removed=previous?.content.cards.filter(card=>!cards.some(current=>current.id===card.id))??[];
  const targets=personas.data?.filter(persona=>["ai1","ai2","ai3","ai4"].includes(persona.id))??[];
  const finished=(value:LabStrategyPublication)=>{
    localStorage.removeItem(storageKey);setPending(undefined);setReceipt(value);setNotFound(false);setMessage("");reload();
  };
  const publish=useMutation({mutationFn:adminLabApi.publishStrategy,onSuccess:finished,onError:error=>{
    const status=responseStatus(error);
    if(status&&status>=400&&status<500&&status!==408) {localStorage.removeItem(storageKey);setPending(undefined);}
    setMessage(getApiErrorMessage(error,status&&status>=400&&status<500&&status!==408?"操作未保存，请调整后重试":"响应未确认。请查询原操作结果，不要创建另一次发布。"));
  }});
  const recover=useMutation({mutationFn:()=>adminLabApi.strategyPublication(pending!.operationId),onSuccess:finished,onError:error=>{
    setNotFound(responseStatus(error)===404);
    setMessage(responseStatus(error)===404?"未找到原操作，可使用相同操作 ID 和参数再次提交。":getApiErrorMessage(error,"原操作结果仍待确认，请稍后再查询。"));
  }});
  const submit=(action:"PUBLISH"|"WITHDRAW")=>{
    if(pending||!personaIds.length||(action==="PUBLISH"&&(!selected||blocked))) return;
    const request:LabStrategyPublicationRequest={operationId:crypto.randomUUID(),action,personaIds:[...personaIds],mode:"TRIAL",...(action==="PUBLISH"?{strategyVersionId:selected!.id}:{})};
    try {localStorage.setItem(storageKey,JSON.stringify(request));} catch {setMessage("无法保存操作标识，本次未提交。请允许浏览器保存站点数据后重试。");return;}
    setPending(request);setMessage("");setNotFound(false);publish.mutate(request);
  };
  const busy=publish.isPending||recover.isPending;
  return <section className="lab-section" aria-label="狼人杀共享策略">
    {view!=="publication"&&<>
    <h2>共享策略版本</h2><p className="lab-note">效果待验证。训练只改变本实验的共享策略；正式房间需在角色试用中选择人格并发布。已有对局保持开局时的策略版本。</p>
    <label className="lab-field">策略版本<select aria-label="策略版本" disabled={versionId!==undefined} value={selected?.id??""} onChange={event=>setSelectedId(event.target.value)}><option value="">尚无策略版本</option>{data.items.map(version=><option key={version.id} value={version.id}>v{version.sequenceNumber}{version.id===data.strategyHeadId?" · 实验当前":""} · {version.createdAt.replace("T"," ").slice(0,16)}</option>)}</select></label>
    {selected?<>
      <details className="lab-small mt-3"><summary>适用规则与模型快照</summary><pre className="whitespace-pre-wrap break-all mt-2">{JSON.stringify(selected.compatibility,null,2)}</pre></details>
      <p className="lab-small mt-3">当前策略覆盖：{roles.map(role=>`${roleName(role)} ${cards.filter(card=>card.role===role).length}`).join(" · ")}</p>
      <p className="lab-small mt-2">与{previous?` v${previous.sequenceNumber}`:"空策略"}比较：新增 {added.length} · 修改 {changed.length} · 移除 {removed.length}</p>
      {selected.previousId&&!previous&&<p className="lab-small">前一版本不在当前列表，无法完整比较。</p>}
      <div className="space-y-3 mt-4">{cards.map(card=><article className="lab-table" key={card.id}>
        <h3 className="font-medium">{roleName(card.role)} · {card.phases.map(phase=>phases[phase]??phase).join(" / ")}</h3><p className="lab-small mt-2">适用情境：{card.condition}</p>
        <p className="whitespace-pre-wrap break-words text-sm leading-7 mt-2">{card.advice}</p>{!!card.exceptions.length&&<p className="lab-small mt-2">不适用：{card.exceptions}</p>}
        {before.has(card.id)&&before.get(card.id)?.advice!==card.advice&&<details className="lab-small mt-2"><summary>查看前版建议</summary><p className="lab-memory">{before.get(card.id)?.advice}</p></details>}
        <details className="lab-small mt-2"><summary>来源证据 · {card.evidence.length} 项</summary><StrategyEvidence items={card.evidence}/></details>
        {!!card.counterEvidence?.length&&<details className="lab-small mt-2"><summary>反例证据 · {card.counterEvidence.length} 项</summary><StrategyEvidence items={card.counterEvidence}/></details>}
        {before.has(card.id)&&JSON.stringify(before.get(card.id)?.counterEvidence??[])!==JSON.stringify(card.counterEvidence??[])&&<details className="lab-small mt-2"><summary>反例变化 · 前版 {before.get(card.id)?.counterEvidence?.length??0} 项 → 当前 {card.counterEvidence?.length??0} 项</summary><StrategyEvidence items={before.get(card.id)?.counterEvidence??[]} previous/></details>}
      </article>)}</div>
      {!!removed.length&&<details className="lab-small mt-3"><summary>已移除建议</summary>{removed.map(card=><p key={card.id}>{roleName(card.role)}：{card.advice}</p>)}</details>}
      {selected.evidence.archiveId&&<Button variant="outline" size="sm" className="mt-4" asChild><Link to={`/admin/replays/${selected.evidence.archiveId}`}>来源对局回放</Link></Button>}
      <StrategyAssessment key={selected.id} experimentId={experimentId} versionId={selected.id} current={assessment} saved={value=>{setSavedAssessments(current=>({...current,[value.strategyVersionId]:value}));reload();}}/>
    </>:<p className="lab-small mt-4">学习成功并生成候选版本后才能查看共享策略，空策略不会发布到正式房间。</p>}
    </>}
    {view!=="strategy"&&<div className="lab-section"><h2>正式角色试用</h2><p className="lab-small mb-3">仅对明确勾选的人格生效；新建普通狼人杀对局使用该策略。撤回后，新局恢复无实验策略。发布并不代表效果已验证。</p>
      {view==="publication"&&<p className="lab-note">{selected?`本次候选：v${selected.sequenceNumber} · ${selected.id}`:"本次训练尚无候选共享策略可发布"} · 效果待验证</p>}
      {personas.error&&<p className="lab-error" role="alert">人格列表读取失败，请刷新后重试。</p>}
      <div className="space-y-3">{targets.map(persona=>{
        const binding=data.bindings.find(item=>item.personaId===persona.id&&item.gameId==="werewolf");
        const version=data.items.find(item=>item.id===binding?.strategyVersionId);
        return <label key={persona.id} className="lab-table flex items-start gap-3"><input type="checkbox" aria-label={`试用人格 ${persona.name}`} checked={personaIds.includes(persona.id)} disabled={!!pending||busy} onChange={event=>setPersonaIds(current=>event.target.checked?[...current,persona.id]:current.filter(id=>id!==persona.id))}/><span><strong>{persona.name}</strong><span className="lab-small block">{binding?.action==="PUBLISH"&&binding.strategyVersionId?`当前试用：${version?`v${version.sequenceNumber}`:binding.strategyVersionId} · 效果待验证`:"当前未绑定实验策略"}</span></span></label>;
      })}</div>
      {blocked&&<p className="lab-error mt-3">该版本人工评审发现问题，发布已停用；可以查看证据、重新评审或选择其他版本。</p>}
      <div className="lab-actions mt-4"><Button disabled={!selected||!personaIds.length||!!pending||busy||blocked} onClick={()=>submit("PUBLISH")}>试用发布到所选人格</Button><Button variant="outline" disabled={!personaIds.length||!!pending||busy} onClick={()=>submit("WITHDRAW")}>撤回所选人格策略</Button></div>
      {pending&&<div className="lab-note mt-4" role="status"><p>{busy?"正在提交或查询操作":"操作结果待确认"} · {pending.operationId}</p><div className="lab-actions mt-2"><Button variant="outline" size="sm" disabled={busy} onClick={()=>recover.mutate()}>查询原操作结果</Button>{notFound&&<Button variant="outline" size="sm" disabled={busy} onClick={()=>publish.mutate(pending)}>使用原操作 ID 重试</Button>}</div></div>}
      {message&&<p className="lab-error mt-3" role="alert">{message}</p>}
      {receipt&&<p className="lab-note mt-3" role="status">{receipt.action==="PUBLISH"?"试用发布已保存，效果待验证":"撤回已保存"} · {receipt.personaIds.length} 个人格 · 操作 {receipt.operationId}</p>}
    </div>}
  </section>;
}
