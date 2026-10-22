import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import Lab from "./Lab";
import { ModelSelectionFields } from "@/components/lab/ModelSelectionFields";
import { LabCostPanel, LabEvaluationResults } from "@/components/lab/LabPanels";
import { labText } from "@/i18n/labTexts";
import type { LabExperiment, LabEvaluationStats } from "@/types/lab";
import type { CatalogModel } from "@/types/aiModelConfig";

let language="zh-CN";
const api=vi.hoisted(()=>({list:vi.fn(),detail:vi.fn(),matches:vi.fn(),enroll:vi.fn(),confirm:vi.fn(),withdraw:vi.fn()}));
vi.mock("@/services/api",()=>({labApi:api,getApiErrorMessage:(_error:unknown,fallback:string)=>fallback}));
vi.mock("react-i18next",()=>({useTranslation:()=>({i18n:{language},t:(key:string)=>key})}));
vi.mock("sonner",()=>({toast:{success:vi.fn(),error:vi.fn()}}));
let container:HTMLDivElement,root:Root,client:QueryClient;
const experiment=():LabExperiment=>({
  id:"experiment",name:"Test laboratory",status:"RUNNING",budgetPoints:1000,createdAt:"2026-10-21T12:00:00",
  attemptedGames:1,reviewedThrough:0,playerCount:6,targetGames:100,wordPack:"daily",hasBlank:false,
  cost:{spentPoints:12,pendingCalls:1,totalCalls:2,actualCost:"0.000001"},
  enrollment:{status:"OFFERED",matchId:"match",confirmUntil:Date.now()+30_000},evaluations:[],
  activeMatches:[{id:"match",sequenceNumber:1,roomId:"room",status:"WAITING",startsAtEpochMs:Date.now()+30000,createdAt:"2026-10-21T12:00:00"}],
});
beforeEach(()=>{
  language="zh-CN";vi.clearAllMocks();
  (globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;
  container=document.createElement("div");document.body.append(container);root=createRoot(container);
  client=new QueryClient({defaultOptions:{queries:{retry:false,gcTime:0}}});
  api.matches.mockResolvedValue({items:[],total:0,page:0});
});
afterEach(()=>{act(()=>root.unmount());client.clear();container.remove();});
async function renderDetail() {
  await act(async()=>{root.render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/lab/experiment"]}><Routes><Route path="/lab/:id" element={<Lab/>}/></Routes></MemoryRouter></QueryClientProvider>);});
  await act(async()=>{await new Promise(resolve=>setTimeout(resolve,20));});
}
it("confirms an offered seat and refreshes persisted enrollment without joining the ordinary room endpoint",async()=>{
  const value=experiment();api.detail.mockImplementation(async()=>({...value}));
  api.confirm.mockImplementation(async()=>{value.enrollment={status:"CONFIRMED",matchId:"match"};});
  await renderDetail();
  const button=Array.from(container.querySelectorAll("button")).find(element=>element.textContent==="确认参战");
  expect(button).toBeDefined();
  await act(async()=>{button!.click();});
  await act(async()=>{await new Promise(resolve=>setTimeout(resolve,20));});
  expect(api.confirm).toHaveBeenCalledExactlyOnceWith("experiment");
  expect(container.textContent).toContain("席位已确认，等待开局");
  expect(container.querySelector('a[href="/room/undercover/room"]')).toBeNull();
});
it("offers a participant entry only after that enrollment has joined the running game",async()=>{
  const value=experiment();value.enrollment={status:"PLAYING",matchId:"match"};
  value.activeMatches![0].status="PLAYING";api.detail.mockResolvedValue(value);
  await renderDetail();
  expect(container.querySelector('a[href="/room/undercover/room"]')?.textContent).toContain("进入对局");
  expect(Array.from(container.querySelectorAll("button")).some(button=>button.textContent==="取消报名")).toBe(false);
});
it("does not offer thinking switches or effort values when provider capabilities are unknown",()=>{
  const model:CatalogModel={id:1,modelKey:"unknown",displayName:"Unknown",provider:"fixture",supportsImageInput:false,supportsStreaming:false,
    thinking:{declared:false,supportsEnable:true,supportsDisable:true,options:[{effort:"HIGH",label:"High"}],defaultEnabled:null,defaultEffort:null}};
  act(()=>root.render(<ModelSelectionFields models={[model]} value={{modelKey:"unknown",thinkingMode:"DEFAULT",thinkingEffort:null}} change={vi.fn()} prefix="fixture"/>));
  const modes=container.querySelector("#fixture-thinking")!;
  expect(modes.querySelectorAll("option")).toHaveLength(1);
  expect(container.querySelector("#fixture-effort")).toBeNull();
  expect(container.textContent).toContain("思考能力未知");
});
it("shows actual charges and pending usage without monetary holds in all supported languages",()=>{
  const value=experiment();
  act(()=>root.render(<LabCostPanel cost={value.cost} limit={100} tr={text=>labText("en",text)}/>));
  expect(container.textContent).toContain("¥0.12");
  expect(container.textContent).toContain("¥0.88");
  expect(container.textContent).toContain("¥0.00");
  expect(container.textContent).toContain("0.000001");
  expect(container.textContent).toContain("awaiting confirmation");
  expect(container.textContent).toContain("Charges use actual usage");
  expect(labText("zh-TW","AI 实验室")).toBe("AI 實驗室");
  expect(labText("zh-CN","PAUSING")).toBe("本局后暂停");
});
it("shows aborted evaluation attempts and role win rates without implying improvement",()=>{
  const stats:LabEvaluationStats={games:1,wins:1,winRate:1,validActionRate:0.8,failureRate:0.5,attemptedGames:2,failedGames:1,
    roles:{CIVILIAN:{games:1,wins:1,winRate:1}}};
  act(()=>root.render(<LabEvaluationResults evaluations={[{id:"evaluation",agentId:"agent",participantId:"copy",status:"PAUSED",pairs:2,completedGames:3,validPairs:1,
    baseline:stats,candidate:{...stats,wins:0,winRate:0},winRateDifference:-1}]} tr={text=>labText("en",text)}/>));
  expect(container.textContent).toContain("Finished attempts / failed");
  expect(container.textContent).toContain("2 / 1");
  expect(container.textContent).toContain("50.0%");
  expect(container.textContent).toContain("100.0% · 1 / 1");
  expect(container.textContent).toContain("failure rates include all finished attempts");
});
