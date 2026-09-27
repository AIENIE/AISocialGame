import { act, useState } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createInstance } from "i18next";
import { I18nextProvider } from "react-i18next";
import { resources } from "@/i18n/resources";
import type { Persona } from "@/types";
import { PersonaBadge, PersonaPicker, type PersonaPickerProps } from "./PersonaPicker";
import { AiSeatControl } from "./AiSeatControl";

const personas: Persona[] = [1, 2, 3, 4].map(i => ({ id: `ai${i}`, name: `角色${i}`, trait: "旧描述", avatar: "", presetVersion: 1 }));
let container: HTMLDivElement, root: Root;
const i18n = createInstance();
beforeEach(async () => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  await i18n.init({ lng: "zh-CN", resources, keySeparator: false, interpolation: { escapeValue: false } });
  container = document.createElement("div"); document.body.appendChild(container); root = createRoot(container);
});
afterEach(() => { act(() => root.unmount()); container.remove(); });
function render(props: Partial<PersonaPickerProps> = {}) {
  const defaults = { personas, selectedAiId: "ai1", onSelectedAiIdChange: vi.fn(), canAddAi: true, onAddAi: vi.fn() };
  act(() => root.render(<I18nextProvider i18n={i18n}><PersonaPicker {...defaults} {...props} /></I18nextProvider>));
}
const addButton = () => container.querySelector<HTMLButtonElement>('[data-testid="game-add-ai-btn"]')!;

describe("complete persona selection", () => {
  it("shows four distinct summaries and expandable behavior, and submits the selected ID once", () => {
    const submit = vi.fn();
    function Harness() {
      const [id, setId] = useState("ai1");
      return <PersonaPicker personas={personas} selectedAiId={id} onSelectedAiIdChange={setId} canAddAi onAddAi={() => submit(id)} />;
    }
    act(() => root.render(<I18nextProvider i18n={i18n}><Harness /></I18nextProvider>));
    expect(container.querySelectorAll('input[type="radio"]')).toHaveLength(4);
    expect(container.querySelectorAll("details")).toHaveLength(4);
    expect(container.textContent).toContain("敢于试探的施压者");
    const details = container.querySelector("details")!;
    act(() => details.querySelector("summary")!.click());
    expect(details.open).toBe(true);
    expect(details.textContent).toContain("改判"); expect(details.textContent).toContain("承诺");
    act(() => container.querySelector<HTMLInputElement>('input[value="ai2"]')!.click());
    act(() => { addButton().click(); addButton().click(); });
    expect(submit).toHaveBeenCalledExactlyOnceWith("ai2");
  });

  it("disables during submission and permits retry after a failed request", () => {
    const submit = vi.fn();
    render({ onAddAi: submit }); act(() => addButton().click());
    render({ onAddAi: submit, isAdding: true });
    expect(addButton().disabled).toBe(true);
    expect(container.querySelector("fieldset")!.disabled).toBe(true);
    expect(addButton().textContent).toBe("正在添加…");
    render({ onAddAi: submit, addError: true });
    expect(container.querySelector('[role="alert"]')!.textContent).toContain("添加失败");
    act(() => addButton().click()); expect(submit).toHaveBeenCalledTimes(2);
  });

  it.each([
    [{ isLoading: true }, "正在加载角色"],
    [{ personas: [] }, "暂无可选角色"],
    [{ full: true }, "房间已满"],
    [{ isHost: false }, "仅房主"],
    [{ isWaiting: false }, "对局开始后"],
  ])("explains a blocked state and prevents adding: %s", (props, expected) => {
    const submit = vi.fn(); render({ ...props, onAddAi: submit });
    expect(container.textContent).toContain(expected); expect(addButton().disabled).toBe(true);
    act(() => addButton().click()); expect(submit).not.toHaveBeenCalled();
  });

  it("does not use stale catalog data after load failure and offers retry", () => {
    const retry = vi.fn(); render({ isError: true, onRetry: retry });
    expect(container.querySelectorAll('input[type="radio"]')).toHaveLength(0);
    expect(addButton().disabled).toBe(true);
    act(() => container.querySelector<HTMLButtonElement>('[role="alert"] button')!.click());
    expect(retry).toHaveBeenCalledOnce();
    render(); expect(addButton().disabled).toBe(false);
  });

  it("rejects a stale selected ID and derives full state from seat count", () => {
    render({ selectedAiId: "removed" }); expect(addButton().disabled).toBe(true);
    act(() => root.render(<I18nextProvider i18n={i18n}><AiSeatControl personas={personas} selectedAiId="ai1" onSelectedAiIdChange={vi.fn()} onAddAi={vi.fn()} canAddAi seatCount={6} maxPlayers={6} /></I18nextProvider>));
    expect(addButton().disabled).toBe(true); expect(container.textContent).toContain("6/6");
  });

  it.each(["zh-CN", "zh-TW", "en"])("localizes all preset descriptions and badges in %s without replacing nicknames", async locale => {
    await act(async () => { await i18n.changeLanguage(locale); });
    act(() => root.render(<I18nextProvider i18n={i18n}><span>生成昵称</span><PersonaBadge personaId="ai1" /><PersonaPicker personas={personas} selectedAiId="ai1" onSelectedAiIdChange={vi.fn()} onAddAi={vi.fn()} canAddAi /></I18nextProvider>));
    expect(container.textContent).toContain("生成昵称");
    const name = resources[locale as keyof typeof resources].translation["persona.ai1.name"];
    expect(container.querySelector('[data-testid="persona-badge"]')!.textContent).toContain(name);
    expect(container.textContent).not.toContain("persona."); expect(container.textContent).not.toContain("旧描述");
  });

  it("uses server descriptions for future versions and a neutral badge for unknown old IDs", () => {
    render({ personas: [{ ...personas[0], presetVersion: 2, name: "未来名称", trait: "新策略摘要", behaviorGuide: { questioning: "未来提问方式" } }] });
    expect(container.textContent).toContain("未来提问方式"); expect(container.textContent).toContain("新策略摘要");
    expect(container.textContent).not.toContain("证据驱动");
    act(() => root.render(<I18nextProvider i18n={i18n}><PersonaBadge personaId="removed" /></I18nextProvider>));
    expect(container.textContent).toContain("默认托管");
  });
});
