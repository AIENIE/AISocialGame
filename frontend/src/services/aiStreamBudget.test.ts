import { afterEach, expect, it, vi } from "vitest";
import { aiApi, getApiErrorCode } from "./api";

afterEach(() => vi.unstubAllGlobals());

it("preserves the budget error code when the server rejects SSE before headers commit", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
    code: "BUDGET_UNAVAILABLE", message: "预算能力暂不可用",
  }), { status: 503, headers: { "Content-Type": "application/json" } })));
  const failure = await aiApi.chatStream([{ role: "user", content: "hello" }], undefined, vi.fn(), vi.fn())
    .catch(error => error as unknown);
  expect(getApiErrorCode(failure)).toBe("BUDGET_UNAVAILABLE");
});
