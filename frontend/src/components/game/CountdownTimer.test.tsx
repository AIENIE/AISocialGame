import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { CountdownTimer } from "./CountdownTimer";

let container: HTMLDivElement, root: Root;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-25T12:00:00Z"));
  container = document.createElement("div"); document.body.append(container); root = createRoot(container);
});
afterEach(() => { act(() => root.unmount()); container.remove(); vi.useRealTimers(); });

it("keeps the last fraction of a second available and labels the exact expiry when requested", () => {
  const deadline = new Date(Date.now() + 1500).toISOString();
  act(() => root.render(<CountdownTimer phaseEndsAt={deadline} expiredLabel="时间已到" />));
  expect(container.textContent).toBe("2s");
  act(() => vi.advanceTimersByTime(1499));
  expect(container.textContent).toBe("1s");
  act(() => vi.advanceTimersByTime(1));
  expect(container.textContent).toBe("时间已到");
  act(() => root.render(<CountdownTimer phaseEndsAt={new Date(Date.now() + 2000).toISOString()} expiredLabel="时间已到" />));
  expect(container.textContent).toBe("2s");
  act(() => root.render(<CountdownTimer expiredLabel="时间已到" />));
  expect(container.textContent).toBe("");
});

it("preserves the existing countdown behavior without the optional expiry label", () => {
  act(() => root.render(<CountdownTimer phaseEndsAt={new Date(Date.now() + 1500).toISOString()} />));
  expect(container.textContent).toBe("1s");
  act(() => vi.advanceTimersByTime(1000));
  expect(container.textContent).toBe("");
});
