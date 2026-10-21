import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import LabAdmin from "./LabAdmin";
import { AdminLabObservation, AdminReplayPage } from "./LabObservation";
import type { LabExperiment } from "@/types/lab";

const api=vi.hoisted(()=>({list:vi.fn(),agents:vi.fn(),detail:vi.fn(),matches:vi.fn(),experiences:vi.fn(),create:vi.fn(),observe:vi.fn(),replay:vi.fn()}));
vi.mock("@/services/api",()=>({adminLabApi:api,adminApi:{modelConfiguration:async()=>({models:[{modelKey:"fixture",displayName:"Fixture",thinking:{declared:false,options:[]}}],functions:[]}),replayEvents:(id:string)=>api.replay(id)},personaApi:{list:async()=>[]},getApiErrorMessage:(_error:unknown,fallback:string)=>fallback}));
vi.mock("sonner",()=>({toast:{success:vi.fn(),error:vi.fn()}}));
let root:Root,container:HTMLDivElement,client:QueryClient;
const selection={modelKey:"fixture",thinkingMode:"DEFAULT" as const,thinkingEffort:null};
function experiment():LabExperiment {
  const roster=Array.from({length:4},(_,index)=>({agentId:"same",participantId:`copy-${index}`,name:`Same #${index+1}`,personaId:"persona",persona:{},modelSelection:selection}));
  const heads=Object.fromEntries(roster.map(profile=>[profile.participantId,{experienceId:`experience-${profile.participantId}`,experience:"strategy",sequenceNumber:1}]));
  return {id:"source",name:"Source",status:"PAUSED",budgetPoints:1000,createdAt:"2026-10-21",attemptedGames:1,reviewedThrough:1,playerCount:4,targetGames:2,wordPack:"daily",hasBlank:false,
    cost:{spentPoints:0,heldPoints:0,pendingPoints:0,pendingCalls:0,totalCalls:0,actualCost:0},configuration:{playerCount:4,targetGames:2,wordPack:"daily",hasBlank:false,roster,coach:selection,experiences:heads,initialExperiences:heads},
    activeMatches:[{id:"match",roomId:"room",sequenceNumber:1,status:"PLAYING",startsAtEpochMs:0,createdAt:"2026-10-21"}]};
}
beforeEach(()=>{
  vi.clearAllMocks();Reflect.set(globalThis,"IS_REACT_ACT_ENVIRONMENT",true);
  container=document.createElement("div");document.body.append(container);root=createRoot(container);
  client=new QueryClient({defaultOptions:{queries:{retry:false,gcTime:0}}});
  api.list.mockResolvedValue({items:[],total:0,page:0});api.agents.mockResolvedValue([{id:"same",name:"Same",personaId:"persona",modelSelection:selection,version:0}]);
  api.detail.mockImplementation(async()=>experiment());api.experiences.mockResolvedValue({items:[],total:0,page:0});
  api.matches.mockResolvedValue({items:[{id:"match",roomId:"room",archiveId:"archive",sequenceNumber:1,status:"REVIEWED",createdAt:"2026-10-21",startsAtEpochMs:0}],total:1,page:0});
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
it("keeps live observation and archive links inside the management entry",async()=>{
  await render("/admin/lab/source");
  expect(container.querySelector('a[href="/admin/lab/source/matches/match"]')).not.toBeNull();
  expect(container.querySelector('a[href="/admin/replays/archive"]')).not.toBeNull();
  expect(container.querySelector('a[href^="/spectate/"]')).toBeNull();expect(container.querySelector('a[href^="/replay/"]')).toBeNull();
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
