import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { Dialog } from "@/components/ui/dialog";
import { localAiDebugApi } from "@/services/api";
import type { AiDebugDetail, AiDebugTurn } from "@/types/aiDebug";
import AiDebugWorkbench from "./AiDebugWorkbench";

vi.mock("@/services/api", () => ({ localAiDebugApi: { instances: vi.fn(), summary: vi.fn(), turns: vi.fn(), detail: vi.fn() } }));
const turn: AiDebugTurn = { id: "job-old", actorId: "ai-1", actorName: "青禾", seatNumber: 1, roleKey: "SPY", round: 1, phase: "DESCRIPTION", kind: "SPEAK", status: "SUCCEEDED", terminalReason: "MODEL_ACTION_APPLIED", modelKey: "fixture-model", promptTokens: 100, completionTokens: 20, cacheReadTokens: 0, cacheWriteTokens: null, reasoningTokens: 12, usageAuthoritative: true, usageComplete: true, attemptCount: 2, responseCount: 2, latencyMs: 1200, createdAt: "2026-10-20T10:00:00", updatedAt: "2026-10-20T10:00:02" };
const detail: AiDebugDetail = { turn, data: { attempts: {
  "0": { attempt: 0, requestId: "job-old:0", state: "RESPONSE", messages: [{ role: "system", content: "完整系统提示" }, { role: "user", content: "{\"privateWord\":\"唯一私有词\",\"memory\":{\"belief\":\"已保存信念\"}}" }], rawOutput: "invalid first output", reasoningText: "上游实际思考", reasoningKind: "TEXT", promptTokens: 100, completionTokens: 20, cacheReadTokens: 0, flags: ["INVALID_JSON_ENVELOPE"] },
  "1": { attempt: 1, requestId: "job-old:1", state: "RESPONSE", rawOutput: "{\"action\":{\"content\":\"修正正文\"}}", parsedOutput: { action: { content: "修正正文" }, evidenceEventIds: ["event-1"] }, reasoningKind: "NOT_PROVIDED", flags: [] },
}, appliedAction: { type: "SPEAK", content: "本局实际采用" } } };
let root: Root, container: HTMLDivElement, client: QueryClient;
const flush = async () => { await act(async () => { await new Promise(resolve => setTimeout(resolve, 30)); }); };
const button = (name: string) => Array.from(document.querySelectorAll<HTMLButtonElement>("button")).find(b => b.textContent === name)!;
async function click(name: string) { await act(async () => button(name).click()); await flush(); }

beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement("div"); document.body.append(container); root = createRoot(container);
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  vi.mocked(localAiDebugApi.instances).mockResolvedValue({ items: [{ id: "match", startedAt: "2026-10-20T10:00:00", turnCount: 1 }], page: 0, size: 20, total: 1 });
  vi.mocked(localAiDebugApi.summary).mockResolvedValue({ instanceId: "match", usage: { turns: 1, attempts: 2, responses: 2, promptTokens: 100, completionTokens: 20, cacheReadTokens: 0, cacheWriteTokens: null, reasoningTokens: 12, complete: true, authoritative: true }, rounds: [{ round: 1, phase: "DESCRIPTION", usage: { turns: 1, attempts: 2, responses: 2, promptTokens: 100, completionTokens: 20, cacheReadTokens: 0, cacheWriteTokens: null, reasoningTokens: 12, complete: true, authoritative: true }, problems: 1 }], actors: [{ id: "ai-1", name: "青禾", seatNumber: 1, roleKey: "SPY" }], captureWarning: null });
  vi.mocked(localAiDebugApi.turns).mockResolvedValue({ items: [turn], page: 0, size: 50, total: 1 });
  vi.mocked(localAiDebugApi.detail).mockImplementation(async (_game, _room, id) => id === turn.id ? detail : { ...detail, turn: { ...turn, id } });
});
afterEach(() => { act(() => root.unmount()); client.clear(); container.remove(); vi.clearAllMocks(); });
async function render() {
  await act(async () => root.render(<QueryClientProvider client={client}><Dialog open><AiDebugWorkbench gameId="undercover" roomId="room" roomName="测试房间" userKey="viewer" instanceId="match" /></Dialog></QueryClientProvider>));
  await flush(); await flush();
}

it("separates exact input, provider thought, each retry and applied action, preserving unknown versus zero", async () => {
  await render();
  expect(document.body.textContent).toContain("唯一私有词");
  await click("思考"); expect(document.body.textContent).toContain("上游实际思考");
  await click("输出"); expect(document.body.textContent).toContain("invalid first output"); expect(document.body.textContent).toContain("本局实际采用");
  const retry = Array.from(document.querySelectorAll<HTMLButtonElement>(".ai-debug-attempts button")).find(b => b.textContent?.includes("重试 1"))!;
  await act(async () => retry.click()); await flush();
  expect(document.body.textContent).toContain("修正正文"); expect(document.body.textContent).not.toContain("invalid first output");
  await click("思考"); expect(document.body.textContent).toContain("本次没有返回可展示的思考文本");
  await act(async () => Array.from(document.querySelectorAll<HTMLElement>("summary")).find(e => e.textContent?.includes("evidenceEventIds"))!.click()); await flush();
  expect(document.body.textContent).toContain("event-1");
  await act(async () => document.querySelector<HTMLButtonElement>(".ai-debug-attempts button")!.click());
  await click("调用详情");
  const usage = document.querySelector(".ai-debug-call-usage")!;
  expect(usage.textContent).toContain("缓存读取（输入子集）0"); expect(usage.textContent).toContain("缓存创建（输入子集）—");
});

it("keeps the chosen action and reading position when new actions arrive, and scopes requests to the chosen role and round", async () => {
  await render();
  await act(async () => document.querySelector<HTMLButtonElement>('[data-testid="ai-debug-turn"]')!.click());
  await click("输出"); const content = document.querySelector<HTMLElement>(".ai-debug-content")!; content.scrollTop = 240;
  vi.mocked(localAiDebugApi.turns).mockResolvedValue({ items: [{ ...turn, id: "job-new", actorName: "新角色" }, turn], page: 0, size: 50, total: 2 });
  await act(async () => { await client.invalidateQueries({ predicate: query => query.queryKey.includes("turns") }); }); await flush();
  expect(document.querySelector('[data-testid="ai-debug-turn"][aria-pressed="true"]')?.textContent).toContain("青禾");
  expect(document.querySelector<HTMLElement>(".ai-debug-content")!.scrollTop).toBe(240);
  const before = vi.mocked(localAiDebugApi.detail).mock.calls.length;
  await act(async () => { await client.invalidateQueries({ predicate: query => query.queryKey.includes("summary") }); }); await flush();
  expect(localAiDebugApi.detail).toHaveBeenCalledTimes(before);
  await act(async () => { const select = document.querySelector<HTMLSelectElement>('[aria-label="筛选角色"]')!; select.value = "ai-1"; select.dispatchEvent(new Event("change", { bubbles: true })); }); await flush();
  expect(vi.mocked(localAiDebugApi.turns).mock.lastCall?.[2].actorId).toBe("ai-1");
  await act(async () => document.querySelector<HTMLButtonElement>('.ai-debug-rounds button:nth-of-type(2)')!.click()); await flush();
  expect(vi.mocked(localAiDebugApi.turns).mock.lastCall?.[2]).toMatchObject({ round: 1, phase: "DESCRIPTION", page: 0 });
});
