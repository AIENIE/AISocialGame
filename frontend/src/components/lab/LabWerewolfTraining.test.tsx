import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { LabWerewolfTraining } from "./LabWerewolfTraining";
import type { LabExperiment, LabStrategyList } from "@/types/lab";
import type { LabTrainingRun } from "@/types/labTraining";

const api=vi.hoisted(()=>({createTrainingRun:vi.fn(),trainingRun:vi.fn(),trainingCases:vi.fn(),controlTrainingRun:vi.fn(),publishStrategy:vi.fn(),strategyPublication:vi.fn(),assessStrategy:vi.fn()}));
vi.mock("@/services/api",()=>({adminLabApi:api,personaApi:{list:async()=>[{id:"ai1",name:"福尔摩斯"}]},getApiErrorMessage:(_error:unknown,fallback:string)=>fallback}));
let root:Root,container:HTMLDivElement,client:QueryClient;
const reload=vi.fn();
function run(overrides:Partial<LabTrainingRun>={}):LabTrainingRun {
  return {id:"run",experimentId:"experiment",operationId:"operation",goal:"RULES_EVIDENCE",status:"DRAFT",stage:"PRACTICE",configuration:{},tasks:[],
    progress:{totalTasks:90,completedTasks:0,failedTasks:0,developmentCases:8,holdoutPairs:32,completedHoldoutPairs:0,gamePairs:2,completedGamePairs:0},
    cost:{spentPoints:233,externalPoints:1,totalKnownPoints:234,budgetPoints:3000,pendingCalls:2,totalCalls:64,actualCost:2.33},assessment:{status:"NOT_RUN"},...overrides};
}
function experiment(withRun=true):LabExperiment {
  return {id:"experiment",name:"训练课程",gameId:"werewolf",status:"PAUSED",trainingRuns:withRun?[run()]:[],activeTrainingRunId:withRun?"run":undefined,budgetPoints:3000,createdAt:"2026-10-26",attemptedGames:1,reviewedThrough:0,playerCount:9,targetGames:2,wordPack:"",hasBlank:false,cost:{spentPoints:233,pendingCalls:2,totalCalls:63,actualCost:2.33}};
}
const strategies:LabStrategyList={items:[{id:"unrelated",experimentId:"experiment",sequenceNumber:9,gameId:"werewolf",content:{cards:[]},compatibility:{},contentHash:"hash",evidence:{},createdAt:"2026-10-26"}],strategyHeadId:"unrelated",bindings:[]};
beforeEach(()=>{
  vi.clearAllMocks();localStorage.clear();Reflect.set(globalThis,"IS_REACT_ACT_ENVIRONMENT",true);
  container=document.createElement("div");document.body.append(container);root=createRoot(container);
  client=new QueryClient({defaultOptions:{queries:{retry:false,gcTime:0}}});
  api.trainingRun.mockResolvedValue(run());api.trainingCases.mockResolvedValue({items:[],coverage:{}});
});
afterEach(()=>{act(()=>root.unmount());client.clear();container.remove();vi.useRealTimers();});
async function render(value=experiment()) {
  await act(async()=>root.render(<QueryClientProvider client={client}><MemoryRouter><LabWerewolfTraining experiment={value} strategies={strategies} reload={reload}/></MemoryRouter></QueryClientProvider>));
  await flush();
}
async function flush(){await act(async()=>{await new Promise(resolve=>setTimeout(resolve,20));});}
function button(name:string){return Array.from(container.querySelectorAll("button")).find(item=>item.textContent===name)!;}
async function click(name:string){await act(async()=>button(name).click());await flush();}
async function step(name:string){await act(async()=>Array.from(container.querySelectorAll<HTMLButtonElement>("nav button")).find(item=>item.textContent?.includes(name))!.click());await flush();}
it("separates historical spending, external diagnostic pricing, unknown usage and independent evaluation",async()=>{
  await render();
  const cost=container.querySelector('[aria-label="训练费用"]')!;
  expect(cost.textContent).toContain("实验累计实扣233 积分");expect(cost.textContent).toContain("独立诊断计价1 积分");
  expect(cost.textContent).toContain("已知费用合计234 积分");expect(cost.textContent).toContain("距停止线2766 积分");expect(cost.textContent).toContain("待确认调用2 笔");
  await step("对照评测");expect(container.textContent).toContain("保留题配对完成0/32");expect(container.textContent).toContain("完整对局配对完成0/2");
  expect(api.controlTrainingRun).not.toHaveBeenCalled();expect(api.publishStrategy).not.toHaveBeenCalled();
});
it("persists operation identity before create and recovers a lost response without another creation",async()=>{
  api.trainingRun.mockRejectedValue({response:{status:404}});
  api.createTrainingRun.mockImplementation(async(_id:string,request:{operationId:string})=>{
    expect(JSON.parse(localStorage.getItem("lab-training-run:experiment")!).operationId).toBe(request.operationId);throw new Error("response lost");
  });
  await render(experiment(false));await click("创建本次训练");
  expect(api.createTrainingRun).toHaveBeenCalledTimes(1);
  const request=api.createTrainingRun.mock.calls[0][1];
  api.trainingRun.mockResolvedValue(run({operationId:request.operationId}));await click("查回原训练");
  expect(api.createTrainingRun).toHaveBeenCalledTimes(1);expect(localStorage.getItem("lab-training-run:experiment")).toBeNull();
  expect(button("试跑一个代表案例")).toBeDefined();expect(api.controlTrainingRun).not.toHaveBeenCalled();
});
it("reloads an unresolved browser operation and retries only the saved identity after an authoritative not-found",async()=>{
  const request={operationId:"saved-original",goal:"RULES_EVIDENCE"};localStorage.setItem("lab-training-run:experiment",JSON.stringify(request));
  api.trainingRun.mockRejectedValue({response:{status:404}});api.createTrainingRun.mockResolvedValue(run({operationId:"saved-original"}));
  await render(experiment(false));expect(api.createTrainingRun).not.toHaveBeenCalled();
  await click("使用原操作 ID 重试创建");expect(api.createTrainingRun).toHaveBeenCalledWith("experiment",request);
});
it("uses the representative case gate and continues the same run only after explicit review",async()=>{
  api.controlTrainingRun.mockResolvedValue(run({status:"PAUSED",reason:"REPRESENTATIVE_CASE_READY"}));
  await render();await click("试跑一个代表案例");expect(api.controlTrainingRun).toHaveBeenLastCalledWith("experiment","operation","start-one");
  expect(container.textContent).toContain("先查看回答、证据与建议");expect(api.controlTrainingRun).toHaveBeenCalledTimes(1);
  api.controlTrainingRun.mockResolvedValue(run({status:"RUNNING"}));await click("案例已检查，继续训练");
  expect(api.controlTrainingRun).toHaveBeenLastCalledWith("experiment","operation","resume");
});
it("requires fetching the original status after a lost control response instead of immediately sending again",async()=>{
  api.controlTrainingRun.mockRejectedValue(new Error("lost"));await render();await click("试跑一个代表案例");
  expect(button("试跑一个代表案例").disabled).toBe(true);expect(api.controlTrainingRun).toHaveBeenCalledTimes(1);
  api.trainingRun.mockResolvedValue(run({status:"RUNNING"}));await click("查询原训练状态");
  expect(button("暂停训练").disabled).toBe(false);expect(api.controlTrainingRun).toHaveBeenCalledTimes(1);
});
it("never substitutes an unrelated strategy head for a missing run candidate or publishes implicitly",async()=>{
  await render();await step("角色试用");
  expect(container.textContent).toContain("尚无候选共享策略可发布");expect(button("试用发布到所选人格").disabled).toBe(true);
  expect(api.publishStrategy).not.toHaveBeenCalled();
});
it("labels an empty assessment as not yet evaluated in both evaluation and trial steps",async()=>{
  api.trainingRun.mockResolvedValue(run({assessment:{}}));await render();
  await step("对照评测");expect(container.textContent).toContain("尚未评测。练习成绩与独立测试分开统计");
  await step("角色试用");expect(container.textContent).toContain("本次评测：尚未评测。");
});
it("shows the blocked teaching task before later pending work",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"RUNNING",tasks:[{id:"first",caseId:"first-case",kind:"PRACTICE",status:"SUCCEEDED",attempt:1,paidRetries:0},{id:"blocked",caseId:"first-case",kind:"TEACH",status:"BLOCKED",attempt:1,paidRetries:0,reason:"AI_USAGE_UNKNOWN"},{id:"next",caseId:"next-case",kind:"PRACTICE",status:"PENDING",attempt:0,paidRetries:0}]}));
  await render();const status=container.querySelector('[aria-label="当前训练状态"]')!;
  expect(status.textContent).toContain("当前任务 · 教练诊断 · 需要处理");expect(status.textContent).toContain("first-case");expect(status.textContent).not.toContain("next-case");
});
it("keeps the next case out of current work until the representative gate is released",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"RUNNING",configuration:{representativeOnly:true},tasks:[{id:"first",caseId:"first-case",kind:"PRACTICE",status:"SUCCEEDED",attempt:1,paidRetries:0},{id:"teach",caseId:"first-case",kind:"TEACH",status:"SUCCEEDED",attempt:1,paidRetries:0},{id:"next",caseId:"next-case",kind:"PRACTICE",status:"PENDING",attempt:0,paidRetries:0}]}));
  await render();const status=container.querySelector('[aria-label="当前训练状态"]')!;
  expect(status.textContent).toContain("最近保存任务 · 教练诊断");expect(status.textContent).not.toContain("next-case");
  expect(api.controlTrainingRun).not.toHaveBeenCalled();
});
it("shows the persisted paid-redo wait without treating the new request as already sent",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"RUNNING",configuration:{representativeOnly:true},tasks:[{id:"first",caseId:"first-case",kind:"PRACTICE",status:"SUCCEEDED",attempt:1,paidRetries:0},{id:"teach",caseId:"first-case",kind:"TEACH",status:"PENDING",attempt:1,paidRetries:1,reason:"PAID_REDO_WAIT",notBeforeEpochMs:Date.now()+60000}]}));
  await render();const status=container.querySelector('[aria-label="当前训练状态"]')!;
  expect(status.textContent).toMatch(/独立计费重试等待：还需 \d+ 秒/);expect(status.textContent).toContain("原请求与费用记录继续保留");expect(status.textContent).toContain("尚未发送");
  expect(api.controlTrainingRun).not.toHaveBeenCalled();
});
it("shows preserved answer, diagnosis, usage and real body timing while keeping raw errors folded",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"PAUSED",reason:"USAGE_UNKNOWN",tasks:[{id:"task",caseId:"case-1",kind:"TEACH",status:"BLOCKED",attempt:1,paidRetries:0,requestId:"original-request",reason:"RPC_INTERNAL_DETAIL",result:{diagnosis:{category:"EVIDENCE",summary:"把猜测当作已知事实",evidenceIds:["event-1"]},changes:[]},diagnostics:{calls:[{requestId:"original-request",state:"RECONCILIATION_REQUIRED",billedPoints:null,timing:{firstTextMs:127000,totalMs:330000}}]}}]}));
  await render();expect(container.textContent).toContain("正文 TTFT：127.0 秒");expect(container.textContent).toContain("此请求实扣：待确认");expect(container.textContent).toContain("把猜测当作已知事实");
  const technical=Array.from(container.querySelectorAll("details")).find(item=>item.querySelector("summary")?.textContent==="请求、用量与技术诊断")!;
  expect(technical.open).toBe(false);expect(technical.textContent).toContain("RPC_INTERNAL_DETAIL");
  expect(container.textContent).toContain("不要另建训练重复生成");
});
it("refreshes the same run cost and timing without starting additional work",async()=>{
  vi.useFakeTimers();
  await act(async()=>root.render(<QueryClientProvider client={client}><MemoryRouter><LabWerewolfTraining experiment={experiment()} strategies={strategies} reload={reload}/></MemoryRouter></QueryClientProvider>));
  await act(async()=>{await vi.advanceTimersByTimeAsync(50);});
  api.trainingRun.mockResolvedValue(run({cost:{...run().cost,spentPoints:240,totalKnownPoints:241,pendingCalls:3}}));
  await act(async()=>{await vi.advanceTimersByTimeAsync(5050);});
  expect(container.querySelector('[aria-label="训练费用"]')!.textContent).toContain("已知费用合计241 积分");
  expect(container.querySelector('[aria-label="训练费用"]')!.textContent).toContain("待确认调用3 笔");
  expect(api.controlTrainingRun).not.toHaveBeenCalled();expect(api.createTrainingRun).not.toHaveBeenCalled();
});
