import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import ModelConfigAdmin from "./ModelConfigAdmin";
import { adminApi } from "@/services/api";
import { HttpApiError } from "@/services/apiError";
import type { AiModelConfiguration, CatalogModel, FunctionModelConfig } from "@/types/aiModelConfig";

vi.mock("@/services/api", async original => ({ ...await original<typeof import("@/services/api")>(),
  adminApi: { modelConfiguration: vi.fn(), syncModelConfiguration: vi.fn(), saveFunctionModelConfig: vi.fn() } }));
vi.mock("sonner", () => ({ toast: { success: vi.fn() } }));
const known: CatalogModel = { id: 12, modelKey: "model-x", displayName: "模型 X", provider: "mock", supportsImageInput: false, supportsStreaming: true,
  thinking: { declared: true, supportsEnable: true, supportsDisable: true, options: [{ effort: "LOW", label: "低" }, { effort: "HIGH", label: "高" }], defaultEnabled: null, defaultEffort: null } };
const unknown: CatalogModel = { ...known, id: 13, modelKey: "unknown", displayName: "未知能力模型", thinking: null };
function fixture(): AiModelConfiguration {
  const functions: FunctionModelConfig[] = ([ ["UNDERCOVER", "谁是卧底"], ["WEREWOLF", "狼人杀"], ["TURTLE_SOUP_PLAYER", "海龟汤玩家"], ["TURTLE_SOUP_HOST", "海龟汤主持人"] ] as const).map(([functionCode, label]) => ({
    functionCode, label, modelKey: "model-x", thinkingMode: "DEFAULT", thinkingEffort: null, version: 0, status: "VALID", statusMessage: "配置有效",
    inferenceEnabled: functionCode === "UNDERCOVER", inferenceMessage: functionCode === "UNDERCOVER" ? "预算推理已启用" : "受现有预算限制，当前未启用推理",
  }));
  return { catalogVersion: 5, syncedAt: "2026-10-19T10:00:00", models: [known, unknown], functions };
}
let root: Root, container: HTMLDivElement;
beforeEach(() => {
  vi.clearAllMocks();
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.mocked(adminApi.modelConfiguration).mockResolvedValue(fixture());
  container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
});
afterEach(() => { act(() => root.unmount()); container.remove(); });
async function render() { await act(async () => { root.render(<ModelConfigAdmin />); }); }
function select(id: string) { return container.querySelector<HTMLSelectElement>(`#${id}`)!; }
async function change(id: string, value: string) { await act(async () => { select(id).value = value; select(id).dispatchEvent(new Event("change", { bubbles: true })); }); }
async function click(text: string, section?: string) {
  const target = section ? container.querySelector(`section[aria-label="${section}"]`)! : container;
  const button = [...target.querySelectorAll("button")].find(item => item.textContent?.includes(text));
  expect(button).toBeDefined(); await act(async () => { button!.click(); });
}
it("reads four independent functions without automatically syncing and keeps unknown defaults explicit", async () => {
  await render();
  expect(container.querySelectorAll("section")).toHaveLength(4);
  expect(container.textContent).toContain("海龟汤主持人"); expect(container.textContent).toContain("受现有预算限制");
  expect(container.textContent).toContain("开关未配置"); expect(container.textContent).toContain("强度未配置");
  expect(adminApi.syncModelConfiguration).not.toHaveBeenCalled();
});
it("only exposes declared efforts and resets thinking when changing to an unknown model", async () => {
  await render(); await change("UNDERCOVER-mode", "ENABLED"); await change("UNDERCOVER-effort", "HIGH");
  expect([...select("UNDERCOVER-effort").options].map(option => option.value)).toEqual(["", "LOW", "HIGH"]);
  await change("UNDERCOVER-model", "unknown");
  expect([...select("UNDERCOVER-mode").options].map(option => option.value)).toEqual(["DEFAULT"]);
  expect(select("UNDERCOVER-effort").disabled).toBe(true); expect(select("UNDERCOVER-effort").value).toBe("");
  vi.mocked(adminApi.saveFunctionModelConfig).mockResolvedValue({ ...fixture().functions[0], modelKey: "unknown", version: 1 });
  await click("保存配置", "谁是卧底");
  expect(adminApi.saveFunctionModelConfig).toHaveBeenCalledWith("UNDERCOVER", { modelKey: "unknown", thinkingMode: "DEFAULT", thinkingEffort: null, catalogVersion: 5, version: 0 });
});
it("saves one row with its versions and clears effort when thinking is disabled", async () => {
  await render(); await change("UNDERCOVER-mode", "ENABLED"); await change("UNDERCOVER-effort", "HIGH");
  await change("UNDERCOVER-mode", "DISABLED");
  vi.mocked(adminApi.saveFunctionModelConfig).mockResolvedValue({ ...fixture().functions[0], thinkingMode: "DISABLED", version: 1 });
  await click("保存配置", "谁是卧底");
  expect(adminApi.saveFunctionModelConfig).toHaveBeenCalledWith("UNDERCOVER", { modelKey: "model-x", thinkingMode: "DISABLED", thinkingEffort: null, catalogVersion: 5, version: 0 });
  expect(select("WEREWOLF-mode").value).toBe("DEFAULT");
});
it("preserves edits after a conflict and only discards the selected row on explicit reread", async () => {
  await render(); await change("UNDERCOVER-mode", "ENABLED"); await change("UNDERCOVER-effort", "HIGH");
  await change("WEREWOLF-model", "unknown");
  vi.mocked(adminApi.saveFunctionModelConfig).mockRejectedValue(new HttpApiError(409,"AI_MODEL_CONFIG_CONFLICT","配置已变化，请重新读取"));
  await click("保存配置", "谁是卧底");
  expect(select("UNDERCOVER-effort").value).toBe("HIGH"); expect(container.textContent).toContain("编辑内容已保留");
  expect(adminApi.modelConfiguration).toHaveBeenCalledTimes(1);
  await click("重新读取此项", "谁是卧底");
  expect(select("UNDERCOVER-mode").value).toBe("DEFAULT"); expect(select("WEREWOLF-model").value).toBe("unknown");
});
it("failed manual sync preserves catalog and unsaved edits", async () => {
  await render(); await change("UNDERCOVER-mode", "ENABLED"); await change("UNDERCOVER-effort", "HIGH");
  vi.mocked(adminApi.syncModelConfiguration).mockRejectedValue(new Error("网关暂不可用"));
  await click("手动同步模型");
  expect(container.textContent).toContain("网关暂不可用"); expect(container.textContent).toContain("模型 X");
  expect(select("UNDERCOVER-effort").value).toBe("HIGH");
});
it("accepts an empty successful directory and keeps invalid selections visible", async () => {
  await render(); const empty = fixture(); empty.models = []; empty.catalogVersion++;
  empty.functions = empty.functions.map(row => ({ ...row, status: "INVALID", statusMessage: "模型已下架" }));
  vi.mocked(adminApi.syncModelConfiguration).mockResolvedValue(empty);
  await click("手动同步模型");
  expect(select("UNDERCOVER-model").value).toBe("model-x");
  expect(container.textContent).toContain("待调整"); expect(container.textContent).toContain("本次同步未返回可见模型");
  expect(container.querySelector('section[aria-label="谁是卧底"] button')?.hasAttribute("disabled")).toBe(true);
});
it("keeps initial environment settings read-only until the first successful sync", async () => {
  const initial = fixture(); initial.syncedAt = null; initial.models = []; initial.catalogVersion = 0;
  initial.functions = initial.functions.map(row => ({ ...row, modelKey: "12", status: "NOT_SYNCED" }));
  vi.mocked(adminApi.modelConfiguration).mockResolvedValue(initial);
  await render(); expect(select("UNDERCOVER-model").disabled).toBe(true);
  vi.mocked(adminApi.syncModelConfiguration).mockResolvedValue(fixture());
  await click("手动同步模型");
  expect(select("UNDERCOVER-model").value).toBe("model-x"); expect(select("UNDERCOVER-mode").value).toBe("DEFAULT");
});

it("distinguishes explicitly disabled and unspecified defaults from missing values", async () => {
  const configured = fixture();
  configured.models = [{ ...known, thinking: { ...known.thinking!, defaultEnabled: false, defaultEffort: "UNSPECIFIED" } }];
  vi.mocked(adminApi.modelConfiguration).mockResolvedValue(configured);
  await render();
  expect(container.textContent).toContain("默认关闭"); expect(container.textContent).toContain("无强度偏好");
  expect(container.textContent).not.toContain("开关未配置"); expect(container.textContent).not.toContain("强度未配置");
});
