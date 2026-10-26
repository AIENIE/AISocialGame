import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import LabAdmin from "./LabAdmin";
import { AdminLabObservation, AdminReplayPage } from "./LabObservation";
import type { LabExperiment, LabStrategyList } from "@/types/lab";

const api=vi.hoisted(()=>({list:vi.fn(),agents:vi.fn(),detail:vi.fn(),matches:vi.fn(),experiences:vi.fn(),create:vi.fn(),observe:vi.fn(),replay:vi.fn(),diagnostics:vi.fn(),updateBudget:vi.fn(),control:vi.fn(),strategies:vi.fn(),assessStrategy:vi.fn(),publishStrategy:vi.fn(),strategyPublication:vi.fn(),evaluate:vi.fn(),evaluateStrategy:vi.fn(),startScenarios:vi.fn(),scenarioRun:vi.fn()}));
vi.mock("@/services/api",()=>({adminLabApi:api,adminApi:{modelConfiguration:async()=>({models:[{modelKey:"fixture",displayName:"Fixture",thinking:{declared:false,options:[]}},{modelKey:"alternate",displayName:"Alternate",thinking:{declared:false,options:[]}}],functions:[]}),replayEvents:(id:string)=>api.replay(id)},personaApi:{list:async()=>[{id:"ai1",name:"福尔摩斯"},{id:"ai2",name:"小丑"},{id:"ai3",name:"华生"},{id:"ai4",name:"露娜"}]},getApiErrorMessage:(_error:unknown,fallback:string)=>fallback}));
vi.mock("sonner",()=>({toast:{success:vi.fn(),error:vi.fn()}}));
let root:Root,container:HTMLDivElement,client:QueryClient;
const selection={modelKey:"fixture",thinkingMode:"DEFAULT" as const,thinkingEffort:null};
function experiment():LabExperiment {
  const roster=Array.from({length:4},(_,index)=>({agentId:"same",participantId:`copy-${index}`,name:`Same #${index+1}`,personaId:"persona",persona:{},modelSelection:selection}));
  const heads=Object.fromEntries(roster.map(profile=>[profile.participantId,{experienceId:`experience-${profile.participantId}`,experience:"strategy",sequenceNumber:1}]));
  return {id:"source",name:"Source",status:"PAUSED",budgetPoints:1000,createdAt:"2026-10-21",attemptedGames:1,reviewedThrough:1,playerCount:4,targetGames:2,wordPack:"daily",hasBlank:false,
    cost:{spentPoints:0,pendingCalls:0,totalCalls:0,actualCost:0},configuration:{playerCount:4,targetGames:2,wordPack:"daily",hasBlank:false,roster,coach:selection,experiences:heads,initialExperiences:heads},
    activeMatches:[{id:"match",roomId:"room",sequenceNumber:1,status:"PLAYING",startsAtEpochMs:0,createdAt:"2026-10-21"}]};
}
beforeEach(()=>{
  vi.clearAllMocks();localStorage.clear();Reflect.set(globalThis,"IS_REACT_ACT_ENVIRONMENT",true);
  container=document.createElement("div");document.body.append(container);root=createRoot(container);
  client=new QueryClient({defaultOptions:{queries:{retry:false,gcTime:0}}});
  api.list.mockResolvedValue({items:[],total:0,page:0});api.agents.mockResolvedValue([{id:"same",name:"Same",personaId:"persona",modelSelection:selection,version:0}]);
  api.detail.mockImplementation(async()=>experiment());api.experiences.mockResolvedValue({items:[],total:0,page:0});
  api.matches.mockResolvedValue({items:[{id:"match",roomId:"room",archiveId:"archive",sequenceNumber:1,status:"REVIEWED",createdAt:"2026-10-21",startsAtEpochMs:0}],total:1,page:0});
  api.strategies.mockResolvedValue(strategyList());api.evaluateStrategy.mockResolvedValue(undefined);api.evaluate.mockResolvedValue(undefined);
  api.create.mockResolvedValue(experiment());api.replay.mockResolvedValue({archive:{roomName:"Admin archive"},events:[]});
});
afterEach(()=>{act(()=>root.unmount());client.clear();container.remove();});
async function render(path:string) {
  await act(async()=>root.render(<QueryClientProvider client={client}><MemoryRouter key={path} initialEntries={[path]}><Routes>
    <Route path="/admin/lab" element={<LabAdmin/>}/><Route path="/admin/lab/:id" element={<LabAdmin/>}/><Route path="/admin/lab/:id/matches/:matchId" element={<AdminLabObservation/>}/><Route path="/admin/replays/:archiveId" element={<AdminReplayPage/>}/>
  </Routes></MemoryRouter></QueryClientProvider>));
  await act(async()=>{await new Promise(resolve=>setTimeout(resolve,20));});
}
function button(name:string) { return Array.from(container.querySelectorAll("button")).find(element=>element.textContent===name)!; }
function choose(selector:string,value:string) { const select=container.querySelector<HTMLSelectElement>(selector)!;select.value=value;select.dispatchEvent(new Event("change",{bubbles:true})); }
function editBudget(value:string) {
  const input=container.querySelector<HTMLInputElement>('[aria-label="修改费用预算"]')!;
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value")!.set!.call(input,value);
  input.dispatchEvent(new Event("input",{bubbles:true}));
}
async function waitForUi(predicate:()=>boolean) {
  for(let tick=0;tick<100&&!predicate();tick++) await act(async()=>{await new Promise(resolve=>setTimeout(resolve,5));});
  expect(predicate()).toBe(true);
}
it("edits a running budget in cents, retains a failed draft and never starts the experiment implicitly",async()=>{
  let current={...experiment(),status:"RUNNING",cost:{...experiment().cost,spentPoints:12,}};
  api.detail.mockImplementation(async()=>current);
  api.updateBudget.mockRejectedValueOnce(new Error("save failed")).mockImplementationOnce(async(_id:string,points:number)=>{current={...current,budgetPoints:points};return current;});
  await render("/admin/lab/source");
  expect(container.querySelector("aside")!.textContent).toContain("¥9.88");
  await act(async()=>button("修改预算").click());
  await act(async()=>editBudget("0.11"));
  await act(async()=>container.querySelector('section[aria-label="预算调整"] form')!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));
  expect(api.updateBudget).not.toHaveBeenCalled();
  await act(async()=>editBudget("20.55"));
  await act(async()=>container.querySelector('section[aria-label="预算调整"] form')!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));
  await waitForUi(()=>container.textContent?.includes("预算保存失败")===true);
  expect(container.querySelector<HTMLInputElement>('[aria-label="修改费用预算"]')!.value).toBe("20.55");
  expect(container.textContent).toContain("预算保存失败");
  await act(async()=>container.querySelector('section[aria-label="预算调整"] form')!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));
  await waitForUi(()=>container.querySelector('[aria-label="修改费用预算"]')===null);
  expect(api.updateBudget).toHaveBeenLastCalledWith("source",2055);expect(api.control).not.toHaveBeenCalled();
  expect(container.querySelector('[aria-label="修改费用预算"]')).toBeNull();
});
it("offers continuing the preserved game and ending it while budget paused",async()=>{
  api.detail.mockResolvedValue({...experiment(),budgetPaused:true,reason:"LAB_BUDGET_EXHAUSTED"});
  await render("/admin/lab/source");
  expect(button("继续当前局")).toBeDefined();expect(button("结束实验")).toBeDefined();
  expect(button("从下一局继续")).toBeUndefined();
});
it("applies player models in bulk, permits individual overrides, and leaves the coach unchanged",async()=>{
  await render("/admin/lab");await act(async()=>button("新建实验").click());
  await act(async()=>choose('select[aria-label="统一玩家模型"]',"alternate"));
  await act(async()=>button("应用到全部 AI 玩家").click());
  expect(container.querySelector<HTMLSelectElement>("#coach-model")!.value).toBe("fixture");
  await act(async()=>choose("#participant-1-model","fixture"));
  await act(async()=>container.querySelector("form")!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));
  const request=api.create.mock.calls[0][0];
  expect(request.participants.map((slot:{selection:{modelKey:string}})=>slot.selection.modelKey)).toEqual(["fixture","alternate","alternate","alternate","alternate","alternate"]);
  expect(request.coach.modelKey).toBe("fixture");
  expect(request.participants.every((slot:{selection:{thinkingMode:string;thinkingEffort:unknown}})=>slot.selection.thinkingMode==="DEFAULT"&&slot.selection.thinkingEffort===null)).toBe(true);
});
it("puts the known historical rule error and settled cost before the history, without a player route",async()=>{
  api.detail.mockResolvedValue({...experiment(),reason:"SUBMISSION_EXCEPTION",latestFailureMatchId:"match",activeMatches:[]});
  api.diagnostics.mockResolvedValue({matchId:"match",sequenceNumber:1,status:"ABORTED",archiveId:"archive",failure:{code:"LEGACY_WORD_SCOPE_REJECTION",stage:"SUBMISSION",actorName:"Same #1",phase:"DESCRIPTION",round:1,modelKey:"fixture",inferred:true,action:{type:"SPEAK",content:"失败原文"}},tasks:[],calls:[{requestId:"original:0",state:"SETTLED",billedPoints:8,}]});
  await render("/admin/lab/source");
  await act(async()=>{await new Promise(resolve=>setTimeout(resolve,25));});
  expect(container.textContent).toContain("旧版禁词范围错误");
  expect(container.textContent).toContain("8 积分");
  expect(container.textContent).toContain("失败原文");
  expect(container.textContent).toContain("从下一局继续");
  expect(container.querySelector('a[href="/admin/replays/archive"]')).not.toBeNull();
  expect(api.diagnostics).toHaveBeenCalledWith("source","match",expect.any(AbortSignal));
});
it("allows every slot to use the same profile and submits no concurrency setting",async()=>{
  await render("/admin/lab");await act(async()=>button("新建实验").click());
  expect(container.querySelectorAll('select[aria-label$="的 AI 档案"]')).toHaveLength(6);
  expect(container.textContent).not.toContain("并发");
  await act(async()=>container.querySelector("form")!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));
  const request=api.create.mock.calls[0][0];expect(request.participants).toHaveLength(6);expect(new Set(request.participants.map((slot:{agentId:string})=>slot.agentId))).toEqual(new Set(["same"]));
  expect(request).not.toHaveProperty("concurrency");expect(request).not.toHaveProperty("agentIds");
});
it("copies the source slot count and each duplicate's own selected experience",async()=>{
  await render("/admin/lab?from=source");
  expect(container.querySelectorAll('select[aria-label$="的 AI 档案"]')).toHaveLength(4);
  const selectors=Array.from(container.querySelectorAll<HTMLSelectElement>('select[aria-label$="的继承经验"]'));
  expect(selectors.map(selector=>selector.value)).toEqual(["experience-copy-0","experience-copy-1","experience-copy-2","experience-copy-3"]);
  await act(async()=>container.querySelector("form")!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));
  expect(api.create.mock.calls[0][0].participants.map((slot:{initialExperienceId:string})=>slot.initialExperienceId)).toEqual(selectors.map(selector=>selector.value));
});
it("shows the frozen coach thinking configuration alongside player settings",async()=>{
  const value=experiment();value.gameId="werewolf";
  value.configuration!.coach={modelKey:"gpt-6.1-sol",thinkingMode:"ENABLED",thinkingEffort:"MAX"};
  value.configuration!.roster=value.configuration!.roster.map(agent=>({...agent,modelSelection:{modelKey:"deepseek-flash",thinkingMode:"ENABLED",thinkingEffort:"LOW"}}));
  api.detail.mockResolvedValue(value);
  await render("/admin/lab/source");await act(async()=>button("阵容与模型").click());
  const coach=Array.from(container.querySelectorAll("p")).find(element=>element.textContent?.startsWith("复盘教练："));
  expect(coach?.textContent).toBe("复盘教练：gpt-6.1-sol · ENABLED MAX");
  expect(container.querySelector("tbody")?.textContent).toContain("ENABLED LOW");
});
it("keeps live observation and archive links inside the management entry",async()=>{
  await render("/admin/lab/source");
  expect(container.querySelector('a[href="/admin/lab/source/matches/match"]')).not.toBeNull();
  expect(container.querySelector('a[href="/admin/replays/archive"]')).not.toBeNull();
  expect(container.querySelector('a[href^="/spectate/"]')).toBeNull();expect(container.querySelector('a[href^="/replay/"]')).toBeNull();
  await act(async()=>button("评测").click());
  const target=Array.from(container.querySelectorAll("label")).find(label=>label.textContent?.startsWith("评测对象"))!.querySelector("select")!;
  expect(Array.from(target.options).map(option=>option.value)).toEqual(["copy-0","copy-1","copy-2","copy-3"]);
});
it("loads public observation without a player provider and uses the existing administrator replay API",async()=>{
  api.observe.mockResolvedValue({match:{id:"match",status:"PLAYING"},room:{name:"Lab room"},state:{phase:"DESCRIPTION",round:1,players:[{playerId:"copy",seatNumber:2,displayName:"Same #1",alive:true}],logs:[{time:"now",message:"公开描述"}]}});
  await render("/admin/lab/source/matches/match");expect(container.textContent).toContain("公开描述");expect(container.textContent).toContain("Same #1");
  expect(api.observe).toHaveBeenCalledWith("source","match",expect.any(AbortSignal));
  await render("/admin/replays/archive");
  expect(api.replay).toHaveBeenCalledWith("archive");expect(container.textContent).toContain("Admin archive");
});

function strategyList():LabStrategyList {
  return {strategyHeadId:"strategy-2",bindings:[],items:[{id:"strategy-2",experimentId:"source",gameId:"werewolf",sequenceNumber:2,previousId:"strategy-1",contentHash:"hash2",compatibility:{},evidence:{archiveId:"archive"},createdAt:"2026-10-26T12:00:00",content:{cards:[{id:"seer-evidence",role:"SEER",phases:["DAY_DISCUSS"],condition:"有查验记录",advice:"引用查验并区分证据与猜测",exceptions:"尚未查验",evidence:[{jobId:"job",actorId:"copy-0",eventIds:["event"]}]}]}},
    {id:"strategy-1",experimentId:"source",gameId:"werewolf",sequenceNumber:1,contentHash:"hash1",compatibility:{},evidence:{},createdAt:"2026-10-25T12:00:00",content:{cards:[]}}]};
}
it("creates a nine-seat shared werewolf experiment with repeated personas and no undercover fields",async()=>{
  await render("/admin/lab");await act(async()=>button("新建实验").click());
  await act(async()=>choose('[aria-label="训练玩法"]',"werewolf"));
  expect(container.querySelectorAll('select[aria-label$="的 AI 档案"]')).toHaveLength(9);
  expect(container.textContent).not.toContain("加入白板玩家");expect(container.textContent).not.toContain("内置词库");
  expect(Array.from(container.querySelector<HTMLSelectElement>('[aria-label="训练席位数"]')!.options).map(option=>option.value)).toEqual(["9","6"]);
  await act(async()=>container.querySelector("form")!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));
  const request=api.create.mock.calls[0][0];
  expect(request).toMatchObject({gameId:"werewolf",learningMode:"SHARED_STRATEGY",playerCount:9,targetGames:2,budgetPoints:3000,rules:{template:"standard",playerCount:9,witchRule:"first_night",winCondition:"side",speechTime:60,hasLastWords:"first_night"}});
  expect(request.participants).toHaveLength(9);expect(new Set(request.participants.map((slot:{agentId:string})=>slot.agentId))).toEqual(new Set(["same"]));
  expect(request).not.toHaveProperty("wordPack");expect(request).not.toHaveProperty("hasBlank");
});
it("copies a werewolf shared strategy without assigning individual undercover experience",async()=>{
  const value=experiment();value.gameId="werewolf";value.playerCount=9;value.configuration!.gameId="werewolf";
  value.configuration!.roster=Array.from({length:9},(_,index)=>({...value.configuration!.roster[0],participantId:`copy-${index}`}));
  api.detail.mockResolvedValue(value);
  await render("/admin/lab?from=source");await waitForUi(()=>container.querySelector<HTMLSelectElement>('[aria-label="初始共享策略"]')?.value==="strategy-2");
  expect(container.querySelectorAll('select[aria-label$="的继承经验"]')).toHaveLength(0);
  await act(async()=>container.querySelector("form")!.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true})));
  expect(api.create.mock.calls[0][0].initialStrategyVersionId).toBe("strategy-2");
  expect(api.create.mock.calls[0][0].participants.every((slot:{initialExperienceId?:string})=>slot.initialExperienceId===undefined)).toBe(true);
});
it("publishes only selected personas and recovers a lost response after navigation without another publication",async()=>{
  api.detail.mockResolvedValue({...experiment(),gameId:"werewolf"});api.publishStrategy.mockRejectedValueOnce(new Error("lost response"));
  await render("/admin/lab/source");await act(async()=>button("共享策略").click());
  await waitForUi(()=>!!container.querySelector('[aria-label="试用人格 福尔摩斯"]'));
  expect(button("试用发布到所选人格").disabled).toBe(true);
  await act(async()=>container.querySelector<HTMLInputElement>('[aria-label="试用人格 福尔摩斯"]')!.click());
  await act(async()=>button("试用发布到所选人格").click());
  await waitForUi(()=>container.textContent?.includes("响应未确认")===true);
  const request=api.publishStrategy.mock.calls[0][0];expect(request).toMatchObject({action:"PUBLISH",strategyVersionId:"strategy-2",personaIds:["ai1"],mode:"TRIAL"});
  expect(JSON.parse(localStorage.getItem("lab-strategy-publication:source")!).operationId).toBe(request.operationId);
  api.strategyPublication.mockResolvedValue({...request,id:"publication",createdAt:"now",bindings:[]});
  await render("/admin/lab");await render("/admin/lab/source");await act(async()=>button("共享策略").click());
  expect(button("试用发布到所选人格").disabled).toBe(true);
  await act(async()=>button("查询原操作结果").click());
  await waitForUi(()=>container.textContent?.includes("试用发布已保存")===true);
  expect(api.strategyPublication).toHaveBeenCalledExactlyOnceWith(request.operationId);expect(api.publishStrategy).toHaveBeenCalledTimes(1);
  expect(localStorage.getItem("lab-strategy-publication:source")).toBeNull();expect(container.textContent).toContain("效果待验证");
});
it("withdraws only explicit personas without sending a strategy version",async()=>{
  api.detail.mockResolvedValue({...experiment(),gameId:"werewolf"});
  api.publishStrategy.mockImplementation(async request=>({...request,id:"withdrawal",createdAt:"now",bindings:[]}));
  await render("/admin/lab/source");await act(async()=>button("共享策略").click());
  await waitForUi(()=>!!container.querySelector('[aria-label="试用人格 露娜"]'));
  await act(async()=>container.querySelector<HTMLInputElement>('[aria-label="试用人格 露娜"]')!.click());
  await act(async()=>button("撤回所选人格策略").click());
  const request=api.publishStrategy.mock.calls[0][0];expect(request).toMatchObject({action:"WITHDRAW",personaIds:["ai4"],mode:"TRIAL"});expect(request).not.toHaveProperty("strategyVersionId");
});
it("evaluates a shared version with a target role and stable operation identity",async()=>{
  const value={...experiment(),gameId:"werewolf" as const,playerCount:9};api.detail.mockResolvedValue(value);api.evaluateStrategy.mockRejectedValueOnce(new Error("lost response")).mockResolvedValueOnce(undefined);
  await render("/admin/lab/source");await act(async()=>button("评测").click());
  await act(async()=>choose('[aria-label="目标游戏身份"]',"HUNTER"));
  await act(async()=>button("启动对照评测").click());
  await waitForUi(()=>container.textContent?.includes("评测响应待确认")===true);
  const first=api.evaluateStrategy.mock.calls[0];expect(first.slice(0,4)).toEqual(["source","copy-0","strategy-2",20]);expect(first[4]).toMatchObject({targetRole:"HUNTER",operationId:expect.any(String)});
  await act(async()=>button("使用原评测请求重试").click());
  expect(api.evaluateStrategy.mock.calls[1]).toEqual(first);expect(api.evaluate).not.toHaveBeenCalled();
});

it("shows scenario execution separately from semantic quality and recovers the original request",async()=>{
  api.detail.mockResolvedValue({...experiment(),gameId:"werewolf",playerCount:9});api.startScenarios.mockRejectedValueOnce(new Error("lost response"));
  api.scenarioRun.mockImplementation(async(_id,operationId)=>({id:"scenario",operationId,status:"COMPLETED",candidateStrategyVersionId:"strategy-2",nextIndex:40,totalCalls:40,results:[
    {caseId:"case-1",personaId:"ai1",role:"SEER",variant:"BASELINE",status:"FAILED",evidenceEventIds:[]},
    {caseId:"case-1",personaId:"ai1",role:"SEER",variant:"CANDIDATE",status:"SUCCEEDED",action:{type:"SPEAK",content:"只根据当前查验判断"},evidenceEventIds:["event-1"]}]}));
  await render("/admin/lab/source");await act(async()=>button("评测").click());
  await act(async()=>button("开始 20 组情境对照").click());
  await waitForUi(()=>container.querySelector('[aria-label="情境对照评测"]')?.textContent?.includes("已完成 1 / 20 组")===true);
  const panel=container.querySelector('[aria-label="情境对照评测"]')!;
  expect(panel.textContent).toContain("RULE_CONSTRUCTED_HOLDOUT");expect(panel.textContent).toContain("事实判断、策略正确性与说服效果仍待人工评阅");expect(panel.textContent).toContain("本评测不计算胜率");
  expect(panel.textContent).toContain("100.0%");expect(panel.textContent).toContain("只根据当前查验判断");
  const request=api.startScenarios.mock.calls[0][1];expect(request).toMatchObject({candidateStrategyVersionId:"strategy-2",operationId:expect.any(String)});
  expect(api.scenarioRun).toHaveBeenCalledWith("source",request.operationId);expect(api.startScenarios).toHaveBeenCalledTimes(1);
});

it("records explicit strategy assessment and prevents publishing a flagged version without blocking withdrawal",async()=>{
  api.detail.mockResolvedValue({...experiment(),gameId:"werewolf"});
  api.assessStrategy.mockImplementation(async(_experimentId,versionId,input)=>({...input,strategyVersionId:versionId,reviewedBy:"reviewer",reviewedAt:"2026-10-26T13:00:00"}));
  await render("/admin/lab/source");await act(async()=>button("共享策略").click());
  await waitForUi(()=>!!container.querySelector('[aria-label="评审规则错误"]'));
  expect(button("保存策略评审").disabled).toBe(true);
  await act(async()=>{choose('[aria-label="评审规则错误"]',"false");choose('[aria-label="评审证据越界"]',"true");choose('[aria-label="评审严重矛盾"]',"false");});
  await act(async()=>button("保存策略评审").click());
  await waitForUi(()=>container.textContent?.includes("发布已停用")===true);
  expect(api.assessStrategy).toHaveBeenCalledExactlyOnceWith("source","strategy-2",{ruleError:false,evidenceLeak:true,severeContradiction:false,notes:""});
  await act(async()=>container.querySelector<HTMLInputElement>('[aria-label="试用人格 福尔摩斯"]')!.click());
  expect(button("试用发布到所选人格").disabled).toBe(true);expect(button("撤回所选人格策略").disabled).toBe(false);
});

it("controls the active scenario independently of the paused experiment",async()=>{
  const scenario={id:"scenario",operationId:"operation",status:"RUNNING" as const,candidateStrategyVersionId:"strategy-2",nextIndex:2,totalCalls:40,results:[]};
  const value={...experiment(),gameId:"werewolf" as const,playerCount:9,configuration:{...experiment().configuration!,activeScenario:"operation",scenarioRuns:{operation:scenario}}};
  api.detail.mockResolvedValue(value);api.control.mockResolvedValue(value);
  await render("/admin/lab/source");
  expect(button("继续实验")).toBeUndefined();expect(button("暂停情境评测")).toBeDefined();
  await act(async()=>button("暂停情境评测").click());expect(api.control).toHaveBeenCalledWith("source","pause");
  api.detail.mockResolvedValue({...value,configuration:{...value.configuration,scenarioRuns:{operation:{...scenario,status:"PAUSED"}}}});
  await render("/admin/lab");await render("/admin/lab/source");
  await waitForUi(()=>!!button("继续情境评测"));
  expect(button("暂停情境评测")).toBeUndefined();
  await act(async()=>button("继续情境评测").click());expect(api.control).toHaveBeenCalledWith("source","resume");
});
it("explains the training checkpoint without displaying a failure and hides nine-seat scenarios on a quick board",async()=>{
  api.detail.mockResolvedValue({...experiment(),gameId:"werewolf",playerCount:6,reason:"TRAINING_CHECKPOINT_REACHED"});
  await render("/admin/lab/source");
  expect(container.textContent).toContain("已达到训练费用检查线");expect(container.textContent).not.toContain("历史记录未提供具体对局");
  await act(async()=>button("评测").click());
  expect(container.querySelector('[aria-label="情境对照评测"]')).toBeNull();
  expect(container.textContent).toContain("6 人快速检查可使用上方整局对照评测");
});
it("uses the frozen scenario manifest count rather than claiming every roster supplies twenty cases",async()=>{
  const value=experiment();api.detail.mockResolvedValue({...value,gameId:"werewolf",playerCount:9,configuration:{...value.configuration,scenarioManifest:Array.from({length:5},(_,index)=>({caseId:`case-${index}`}))}});
  await render("/admin/lab/source");await act(async()=>button("评测").click());
  expect(button("开始 5 组情境对照")).toBeDefined();
  expect(container.querySelector('[aria-label="情境对照评测"]')?.textContent).toContain("共 10 次调用");
});

it("shows counter-evidence and its version change while preserving legacy cards without invented examples",async()=>{
  const strategies=strategyList(),card=strategies.items[0].content.cards[0];
  strategies.items[1].content.cards=[{...card}];
  strategies.items[0].content.cards=[{...card,counterEvidence:[{jobId:"counter-job",actorId:"counter-actor",eventIds:["counter-event"]}]}];
  api.detail.mockResolvedValue({...experiment(),gameId:"werewolf"});api.strategies.mockResolvedValue(strategies);
  await render("/admin/lab/source");await act(async()=>button("共享策略").click());
  await waitForUi(()=>container.textContent?.includes("反例证据 · 1 项")===true);
  expect(container.textContent).toContain("反例变化 · 前版 0 项 → 当前 1 项");expect(container.textContent).toContain("counter-job");expect(container.textContent).toContain("counter-event");expect(container.textContent).toContain("修改 1");
  await act(async()=>choose('[aria-label="策略版本"]',"strategy-1"));
  expect(container.textContent).not.toContain("反例证据");expect(container.textContent).not.toContain("counter-event");
});
it("distinguishes twenty persona situations from their five construction sources",async()=>{
  const value=experiment();api.detail.mockResolvedValue({...value,gameId:"werewolf",playerCount:9,configuration:{...value.configuration,scenarioManifest:Array.from({length:20},(_,index)=>({caseId:`case-${index}`,sourceGroup:`family-${index%5}`}))}});
  await render("/admin/lab/source");await act(async()=>button("评测").click());
  const panel=container.querySelector('[aria-label="情境对照评测"]')!;
  expect(panel.textContent).toContain("20 个情境单元来自 5 种构造来源");expect(panel.textContent).toContain("共 40 次调用");expect(panel.textContent).toContain("人格变体不计为独立来源");
});
