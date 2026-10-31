import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { HttpApiError } from "@/services/apiError";
import ExchangeCard from "./ExchangeCard";
vi.mock("react-i18next", () => ({ useTranslation: () => ({ t: (key: string, values?: { id?: string }) => key + (values?.id ?? "") }) }));
let element: HTMLDivElement, root: ReturnType<typeof createRoot>;
beforeEach(() => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  element = document.createElement("div"); document.body.append(element); root = createRoot(element);
});
afterEach(() => { act(() => root.unmount()); element.remove(); });
async function prepare(code: string) {
  const exchange = vi.fn().mockRejectedValueOnce(new HttpApiError(503, code, "fixture failure")).mockResolvedValue(undefined);
  act(() => root.render(<ExchangeCard exchanging={false} onExchange={exchange} />));
  const input = element.querySelector("input")!;
  act(() => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, "30"); input.dispatchEvent(new Event("input", { bubbles: true })); });
  await act(async () => { element.querySelector("button")!.click(); });
  return exchange;
}
it("shows an unknown result with the original request ID and reuses that identity", async () => {
  const exchange = await prepare("CREDIT_EXCHANGE_RESULT_UNKNOWN");
  const requestId = exchange.mock.calls[0][1];
  expect(element.querySelector('[role="status"]')?.textContent).toContain("wallet.exchangeUnknown");
  expect(element.textContent).toContain(requestId);
  await act(async () => { element.querySelector("button")!.click(); });
  expect(exchange.mock.calls[1][1]).toBe(requestId);
});
it("allows a fresh identity after a confirmed rejection", async () => {
  const exchange = await prepare("CREDIT_EXCHANGE_REJECTED");
  expect(element.querySelector('[role="status"]')).toBeNull();
  await act(async () => { element.querySelector("button")!.click(); });
  expect(exchange.mock.calls[1][1]).not.toBe(exchange.mock.calls[0][1]);
});
