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
it("starts evaluation-only calibration without offering a paid teaching representative",async()=>{
  api.trainingRun.mockResolvedValue(run({stage:"CALIBRATION",configuration:{mode:"EVALUATE_ONLY"},coverage:{plannedPairs:32,scoredPairs:0,unscoredPairs:32,failedPlayerRequests:0}}));
  api.controlTrainingRun.mockResolvedValue(run({status:"RUNNING",stage:"CALIBRATION",configuration:{mode:"EVALUATE_ONLY"}}));
  await render();expect(container.textContent).toContain("五项协议校准");expect(button("试跑一个代表案例")).toBeUndefined();
  await click("开始校准与独立复测");expect(api.controlTrainingRun).toHaveBeenCalledWith("experiment","operation","start");
});
it("requires an exact candidate before creating another independent evaluation",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"COMPLETED",stage:"REPORT"}));
  api.createTrainingRun.mockImplementation(async(_id:string,request:Record<string,unknown>)=>{expect(JSON.parse(localStorage.getItem("lab-training-run:experiment")!)).toEqual(request);return run({operationId:request.operationId as string,stage:"CALIBRATION",configuration:{mode:"EVALUATE_ONLY"}});});
  await render();await step("选课程");expect(button("创建独立复测").disabled).toBe(true);
  await act(async()=>{const select=container.querySelector<HTMLSelectElement>('#evaluation-candidate')!;select.value="unrelated";select.dispatchEvent(new Event("change",{bubbles:true}));});
  await click("创建独立复测");expect(api.createTrainingRun).toHaveBeenCalledWith("experiment",expect.objectContaining({mode:"EVALUATE_ONLY",candidateStrategyVersionId:"unrelated",materialSetVersion:"rules-evidence-eval-v2.1"}));
  expect(api.publishStrategy).not.toHaveBeenCalled();
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
it("starts the normal automatic course directly while keeping the representative trial optional",async()=>{
  api.controlTrainingRun.mockResolvedValue(run({status:"RUNNING",configuration:{representativeOnly:false}}));
  await render();expect(container.textContent).toContain("开始课程后自动推进练习、策略合并、回测和独立评测");
  expect(button("试跑一个代表案例")).toBeDefined();await click("开始课程");
  expect(api.controlTrainingRun).toHaveBeenCalledExactlyOnceWith("experiment","operation","start");
  expect(button("暂停训练")).toBeDefined();expect(button("案例已检查，继续训练")).toBeUndefined();
  expect(api.createTrainingRun).not.toHaveBeenCalled();
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
it("distinguishes ongoing holdout evaluation from conditional full-game evaluation before aggregation",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"RUNNING",stage:"HOLDOUT",assessment:{},candidateStrategyVersionId:"candidate",progress:{...run().progress,completedHoldoutPairs:4}}));
  await render();expect(container.textContent).toContain("保留题配对完成4/32");
  expect(container.textContent).toContain("正在评测，结果尚未汇总");expect(container.textContent).not.toContain("尚未评测");expect(container.textContent).not.toContain("尚未运行对照评测");
  expect(container.textContent).toContain("目标错误：基线 未评分 → 候选 未评分");expect(container.textContent).not.toContain("仅已评分");
  const games=container.querySelector('[aria-label="完整九人对局"]')!;
  expect(games.textContent).toContain("完整九人对局（达标后执行）");expect(games.textContent).toContain("独立保留题完整评测并达到改善标准后，自动执行 2 组成对的九人对局");
  await step("角色试用");expect(container.textContent).toContain("本次评测：正在评测，结果尚未汇总。");
  expect(api.controlTrainingRun).not.toHaveBeenCalled();expect(api.publishStrategy).not.toHaveBeenCalled();
});
it("closes an incomplete evaluation without implying success or counting skipped tasks as failures",async()=>{
  const tasks:LabTrainingRun["tasks"]=Array.from({length:122},(_,index)=>({id:`task-${index}`,caseId:`case-${index}`,kind:"PRACTICE",status:index<72?"SUCCEEDED":index<102?"FAILED":"SKIPPED",attempt:0,paidRetries:0}));
  api.trainingRun.mockResolvedValue(run({status:"COMPLETED",stage:"REPORT",reason:"HOLDOUT_INCOMPLETE",tasks,
    progress:{...run().progress,totalTasks:122,completedTasks:72,failedTasks:50,completedHoldoutPairs:18},
    assessment:{status:"INCOMPLETE",completedPairs:18,plannedPairs:32,baselineErrors:6,candidateErrors:3,newRuleErrors:0,newEvidenceLeaks:0}}));
  await render();
  expect(container.querySelector("header .lab-state")?.textContent).toBe("已收尾 · 评测未完成");
  expect(container.querySelector('[aria-label="当前训练状态"]')?.textContent).toContain("已处理 122/122 · 成功 72 · 失败 30 · 跳过 20");
  expect(container.textContent).toContain("仅已评分 18/32 对 · 目标错误：基线 6 → 候选 3");
  expect(container.textContent).toContain("评测未完整完成");expect(container.textContent).not.toContain("失败 50");
  expect(button("继续当前训练")).toBeUndefined();expect(api.controlTrainingRun).not.toHaveBeenCalled();expect(api.publishStrategy).not.toHaveBeenCalled();
});
it("shows recorded full-game results without replacing a completed holdout assessment with a running label",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"RUNNING",stage:"GAMES",assessment:{status:"IMPROVED"},candidateStrategyVersionId:"candidate"}));
  const stats={games:0,wins:0,winRate:0,validActionRate:0,failureRate:0,attemptedGames:0,failedGames:0,roles:{}};
  const value=experiment();value.evaluations=[{id:"paired-games",agentId:"agent",participantId:"participant",status:"RUNNING",pairs:1,completedGames:0,validPairs:0,baseline:stats,candidate:stats,winRateDifference:0,candidateStrategyVersionId:"candidate"}];
  await render(value);expect(container.textContent).toContain("在本次样本中观察到改善");expect(container.textContent).not.toContain("正在评测，结果尚未汇总");
  const games=container.querySelector('[aria-label="完整九人对局"]')!;
  expect(games.querySelector("table")).not.toBeNull();expect(games.textContent).not.toContain("本次尚无完整对局结果");
  expect(api.controlTrainingRun).not.toHaveBeenCalled();
});
it("shows the blocked teaching task before later pending work",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"RUNNING",tasks:[{id:"first",caseId:"first-case",kind:"PRACTICE",status:"SUCCEEDED",attempt:1,paidRetries:0},{id:"blocked",caseId:"first-case",kind:"TEACH",status:"BLOCKED",attempt:1,paidRetries:0,reason:"AI_USAGE_UNKNOWN"},{id:"next",caseId:"next-case",kind:"PRACTICE",status:"PENDING",attempt:0,paidRetries:0}]}));
  await render();const status=container.querySelector('[aria-label="当前训练状态"]')!;
  expect(status.textContent).toContain("当前任务 · 教练诊断 · 需要处理");expect(status.textContent).toContain("first-case");expect(status.textContent).not.toContain("next-case");
  expect(status.textContent).toContain("已处理 1/3 · 成功 1 · 失败 0 · 跳过 0");
});
it("keeps the next case out of current work until the representative gate is released",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"RUNNING",configuration:{representativeOnly:true},tasks:[{id:"first",caseId:"first-case",kind:"PRACTICE",status:"SUCCEEDED",attempt:1,paidRetries:0},{id:"teach",caseId:"first-case",kind:"TEACH",status:"SUCCEEDED",attempt:1,paidRetries:0},{id:"next",caseId:"next-case",kind:"PRACTICE",status:"PENDING",attempt:0,paidRetries:0}]}));
  await render();const status=container.querySelector('[aria-label="当前训练状态"]')!;
  expect(status.textContent).toContain("最近保存任务 · 教练诊断");expect(status.textContent).not.toContain("next-case");
  expect(api.controlTrainingRun).not.toHaveBeenCalled();
});
it("retains an oversized second development case without counting its replacement as a ninth exercise",async()=>{
  const material=Array.from({length:40},(_,index)=>({id:`case-${index}`,purpose:index<8?"DEVELOPMENT":"HOLDOUT",sourceType:"ARCHIVED",sourceGroup:`source-${index<8?index:Math.floor(index/4)}`,personaId:"ai1",participantId:"p1",contentHash:`hash-${index}`,data:{}}));
  api.trainingCases.mockResolvedValue({items:material,coverage:{development:8,holdout:32},caseReplacements:{}});
  api.trainingRun.mockResolvedValue(run({status:"RUNNING",configuration:{caseReplacements:{}},tasks:[]}));
  await render();expect(api.trainingCases).toHaveBeenCalledTimes(1);
  const replacements={"case-1":"replacement-case"};
  api.trainingCases.mockResolvedValue({items:[...material,{...material[1],id:"replacement-case",contentHash:"replacement-hash"}],coverage:{development:8,holdout:32,excluded:1},caseReplacements:replacements});
  api.trainingRun.mockResolvedValue(run({status:"RUNNING",configuration:{caseReplacements:replacements},progress:{...run().progress,totalTasks:18},tasks:[
    {id:"old-practice",caseId:"case-1",kind:"PRACTICE",status:"SKIPPED",reason:"MATERIAL_TOO_LARGE",attempt:0,paidRetries:0,requestId:null,diagnostics:{inputCodePoints:33330,calls:[]}},
    {id:"old-teach",caseId:"case-1",kind:"TEACH",status:"SKIPPED",reason:"MATERIAL_TOO_LARGE",attempt:0,paidRetries:0,requestId:null,diagnostics:{calls:[]}},
    {id:"replacement-practice",caseId:"replacement-case",kind:"PRACTICE",status:"RUNNING",attempt:0,paidRetries:0}]}));
  await click("查询原训练状态");await flush();
  expect(api.trainingCases).toHaveBeenCalledTimes(2);
  expect(container.querySelector('[aria-label="当前训练状态"]')?.textContent).toContain("replacement-case");
  expect(container.textContent).toContain("当前开发练习 8 个案例 · 独立保留题 32 个单元");
  expect(container.textContent).toContain("已替换材料 1 份，不计入开发练习题数");expect(container.textContent).toContain("case-1 · 材料过大，已替换");
  expect(container.textContent).toContain("原材料与任务记录保留，不计为额外开发题");expect(container.textContent).toContain("已处理 2/3 · 成功 0 · 失败 0 · 跳过 2");
  expect(container.querySelectorAll('[role="alert"]')).toHaveLength(0);expect(api.controlTrainingRun).not.toHaveBeenCalled();
});
it("follows a replacement chain from the first development case when selecting representative work",async()=>{
  const material=["original","intermediate","active","next"].map(id=>({id,purpose:"DEVELOPMENT",sourceType:"ARCHIVED",sourceGroup:"same-source",personaId:"ai1",participantId:"p1",contentHash:id,data:{}}));
  api.trainingCases.mockResolvedValue({items:material,coverage:{development:2,excluded:2},caseReplacements:{original:"intermediate",intermediate:"active"}});
  api.trainingRun.mockResolvedValue(run({status:"RUNNING",configuration:{representativeOnly:true},tasks:[
    {id:"old",caseId:"original",kind:"PRACTICE",status:"SKIPPED",reason:"MATERIAL_TOO_LARGE",attempt:0,paidRetries:0},
    {id:"middle",caseId:"intermediate",kind:"PRACTICE",status:"SKIPPED",reason:"MATERIAL_TOO_LARGE",attempt:0,paidRetries:0},
    {id:"later",caseId:"next",kind:"PRACTICE",status:"PENDING",attempt:0,paidRetries:0},
    {id:"selected",caseId:"active",kind:"PRACTICE",status:"RUNNING",attempt:0,paidRetries:0}]}));
  await render();const status=container.querySelector('[aria-label="当前训练状态"]')!;
  for(let tick=0;tick<10&&!status.textContent?.includes("玩家练习 · active");tick++)await flush();
  expect(status.textContent).toContain("当前任务 · 玩家练习 · 进行中");expect(status.textContent).toContain("玩家练习 · active");expect(status.textContent).not.toContain("玩家练习 · next");
  expect(container.textContent).toContain("当前开发练习 2 个案例");expect(container.textContent).toContain("已替换材料 2 份");
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
it("explains the observed pre-send material failure and preserves real timing, costs and all five steps",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"PAUSED",reason:"REPRESENTATIVE_CASE_FAILED",configuration:{representativeOnly:true},assessment:{},
    progress:{...run().progress,totalTasks:16,completedTasks:1,failedTasks:1},cost:{...run().cost,spentPoints:235,totalKnownPoints:236,totalCalls:65},
    tasks:[{id:"practice",caseId:"case-one",kind:"PRACTICE",status:"SUCCEEDED",attempt:0,paidRetries:0,requestId:"practice:0",result:{speech:"保留首个玩家回答"},diagnostics:{calls:[{requestId:"practice:0",state:"SETTLED",billedPoints:2,timing:{firstTextMs:2631,totalMs:4232}}]}},
      {id:"teach",caseId:"case-one",kind:"TEACH",status:"FAILED",reason:"MATERIAL_TOO_LARGE",requestId:null,attempt:0,paidRetries:0,startedAtEpochMs:null,diagnostics:{inputCodePoints:40001,calls:[]}},
      {id:"next",caseId:"case-two",kind:"PRACTICE",status:"PENDING",attempt:0,paidRetries:0}]}));
  await render();const status=container.querySelector('[aria-label="当前训练状态"]')!,cost=container.querySelector('[aria-label="训练费用"]')!;
  expect(status.textContent).toContain("代表案例未完成，训练已暂停");expect(status.textContent).toContain("教练诊断 · 失败");expect(status.textContent).not.toContain("case-two");
  expect(status.querySelector('[role="alert"]')?.textContent).toContain("输入材料超过 32000 Unicode 字符上限（当前 40001 字符）");
  expect(status.querySelector('[role="alert"]')?.textContent).toContain("本任务未发送模型请求，未产生调用费用");
  expect(container.textContent).toContain("保留首个玩家回答");expect(container.textContent).toContain("正文 TTFT：2.6 秒 · 总耗时：4.2 秒 · 此请求实扣：2 积分");
  expect(cost.textContent).toContain("实验累计实扣235 积分");expect(cost.textContent).toContain("独立诊断计价1 积分");expect(cost.textContent).toContain("已知费用合计236 积分");expect(cost.textContent).toContain("待确认调用2 笔");
  expect(container.querySelectorAll('[aria-label="狼人杀训练步骤"] button')).toHaveLength(5);
  await step("选课程");expect(container.textContent).toContain("规则与证据课程");
  await step("策略变化");expect(container.textContent).toContain("学习成功并生成候选版本后才能查看共享策略");
  await step("对照评测");expect(container.textContent).toContain("尚未评测");expect(container.textContent).toContain("保留题配对完成0/32");
  await step("角色试用");expect(button("试用发布到所选人格").disabled).toBe(true);
  expect(api.controlTrainingRun).not.toHaveBeenCalled();expect(api.publishStrategy).not.toHaveBeenCalled();
});
it("does not label an oversize task free when an original request exists or call records are unavailable",async()=>{
  api.trainingRun.mockResolvedValue(run({status:"PAUSED",tasks:[{id:"teach",caseId:"case-one",kind:"TEACH",status:"FAILED",reason:"MATERIAL_TOO_LARGE",requestId:"original",attempt:0,paidRetries:0,diagnostics:{inputCodePoints:40001,calls:[{requestId:"original",state:"RECONCILIATION_REQUIRED",billedPoints:null}]}},{id:"missing",caseId:"case-two",kind:"TEACH",status:"FAILED",reason:"MATERIAL_TOO_LARGE",attempt:0,paidRetries:0}]}));
  await render();expect(container.textContent).toContain("输入材料超过 32000");expect(container.textContent).toContain("此请求实扣：待确认");
  expect(container.textContent).not.toContain("未产生调用费用");expect(api.controlTrainingRun).not.toHaveBeenCalled();
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
