import { afterEach, expect, it, vi } from "vitest";
import { aiApi, getApiErrorCode, setAuthToken, subscribeAuthExpired } from "./api";

afterEach(() => { vi.unstubAllGlobals(); setAuthToken(undefined); });

it("preserves the budget error code when the server rejects SSE before headers commit", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
    code: "BUDGET_UNAVAILABLE", message: "预算能力暂不可用",
  }), { status: 503, headers: { "Content-Type": "application/json" } })));
  const failure = await aiApi.chatStream([{ role: "user", content: "hello" }], undefined, vi.fn(), vi.fn())
    .catch(error => error as unknown);
  expect(getApiErrorCode(failure)).toBe("BUDGET_UNAVAILABLE");
});

it("cancels the previous account stream and ignores its late unauthorized response", async () => {
  const expired = vi.fn();
  const unsubscribe = subscribeAuthExpired(expired);
  let complete!: (response: Response) => void;
  const fetchMock = vi.fn<typeof fetch>().mockImplementation(() => new Promise(resolve => { complete = resolve; }));
  vi.stubGlobal("fetch", fetchMock);
  try {
    setAuthToken("A");
    const stream = aiApi.chatStream([], undefined, vi.fn(), vi.fn()).catch(error => error as unknown);
    const signal = fetchMock.mock.calls[0][1]?.signal;
    setAuthToken("B");
    expect(signal?.aborted).toBe(true);
    complete(new Response("{}", { status: 401 }));
    await stream;
    expect(expired).not.toHaveBeenCalled();
    fetchMock.mockResolvedValue(new Response("{}", { status: 403 }));
    await expect(aiApi.chatStream([], undefined, vi.fn(), vi.fn())).rejects.toMatchObject({ status: 403 });
    expect(expired).not.toHaveBeenCalled();
    fetchMock.mockResolvedValue(new Response("{}", { status: 401 }));
    await expect(aiApi.chatStream([], undefined, vi.fn(), vi.fn())).rejects.toMatchObject({ status: 401 });
    expect(expired).toHaveBeenCalledTimes(1);
  } finally { unsubscribe(); }
});
