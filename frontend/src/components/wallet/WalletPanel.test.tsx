import { act } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import WalletPanel from "./WalletPanel";
const mocks = vi.hoisted(() => ({ balance: vi.fn(), status: vi.fn(), usage: vi.fn(), ledger: vi.fn(), redemptions: vi.fn(), exchanges: vi.fn(), update: vi.fn() }));
vi.mock("@/services/api", () => ({ walletApi: { getBalance: mocks.balance, getCheckinStatus: mocks.status, getUsageRecords: mocks.usage, getLedger: mocks.ledger, getRedemptionHistory: mocks.redemptions, getExchangeHistory: mocks.exchanges }, getApiErrorMessage: () => "failed" }));
vi.mock("@/hooks/useAuth", () => ({ useAuth: () => ({ user: { id: "self" }, updateBalance: mocks.update }) }));
vi.mock("react-i18next", async importOriginal => ({ ...await importOriginal<typeof import("react-i18next")>(), useTranslation: () => ({ t: (key: string) => key }) }));
let element: HTMLDivElement, root: ReturnType<typeof createRoot>, client: QueryClient;
beforeEach(() => { vi.clearAllMocks(); (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true; element = document.createElement("div"); document.body.append(element); root = createRoot(element); client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  mocks.status.mockResolvedValue({ checkedInToday: false }); for (const fn of [mocks.usage, mocks.ledger, mocks.redemptions, mocks.exchanges]) fn.mockResolvedValue({ items: [], total: 0 }); });
afterEach(() => { act(() => root.unmount()); client.clear(); element.remove(); });
async function render() { await act(async () => { root.render(<QueryClientProvider client={client}><WalletPanel /></QueryClientProvider>); await new Promise(resolve => setTimeout(resolve, 30)); }); await act(async () => { await new Promise(resolve => setTimeout(resolve, 30)); }); }
it("shows a confirmed zero balance and marks it as available", async () => { const zero = { totalTokens: 0, projectPermanentTokens: 0, projectTempTokens: 0 }; mocks.balance.mockResolvedValue(zero); await render(); expect(element.textContent).toContain("wallet.balanceTitle"); expect(mocks.update).toHaveBeenCalledWith(zero); });
it("does not invent zero balances or hide successful records when balance reading fails", async () => { mocks.balance.mockRejectedValue(new Error("down")); mocks.redemptions.mockResolvedValue({ items: [{ code: "REAL_CODE", tokensGranted: 3, creditType: "permanent" }] }); await render(); expect(element.textContent).not.toContain("wallet.balanceTitle"); expect(element.textContent).toContain("data.failed"); expect(element.textContent).toContain("REAL_CODE"); expect(mocks.update).toHaveBeenCalledWith(undefined); });
