import { act } from "react";
import { createRoot } from "react-dom/client";
import { expect, it, vi } from "vitest";
import SafetyAdmin from "./SafetyAdmin";
import { AdminReplayEvidence } from "./AdminReplayEvidence";

vi.mock("@/services/api", () => ({ adminApi: {
  safetySummary: vi.fn().mockResolvedValue({}),
  safetyEvents: vi.fn().mockResolvedValue({ items: [], total: 0 }),
  safetyControls: vi.fn().mockResolvedValue([]),
  replayEvents: vi.fn().mockResolvedValue({ events: [] }),
} }));

it("renders standalone admin safety and replay without a player i18n provider", async () => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  try {
    await act(async () => root.render(<><SafetyAdmin /><AdminReplayEvidence archiveId="test" /></>));
    expect(container.textContent).toContain("AI 安全与应急运营");
    expect(container.textContent).toContain("暂无活跃控制。");
    const button = Array.from(container.querySelectorAll("button")).find(b => b.textContent === "管理端完整回放")!;
    await act(async () => button.click());
    expect(container.textContent).toContain("仅管理端可见的事件证据");
  } finally { act(() => root.unmount()); container.remove(); }
});
