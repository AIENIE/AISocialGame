import { act } from "react";
import { createRoot } from "react-dom/client";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { afterEach, expect, it, vi } from "vitest";
import { SessionQueries } from "./SessionQueries";

const identity = vi.hoisted(() => ({ user: { id: "A" } as { id: string } | null, token: "token-A" }));
vi.mock("@/hooks/useAuth", () => ({ useAuth: () => identity }));
let root: ReturnType<typeof createRoot> | undefined;
afterEach(() => { act(() => root?.unmount()); root = undefined; });

it("cancels old requests and removes cached private data when identity changes", async () => {
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  root = createRoot(document.createElement("div"));
  let client!: QueryClient;
  function Probe() { client = useQueryClient(); return null; }
  const render = () => act(() => root!.render(<SessionQueries><Probe /></SessionQueries>));
  render();
  const previous = client;
  previous.setQueryData(["room", "same-room"], "A_PRIVATE");
  let signal!: AbortSignal;
  const pending = previous.fetchQuery({ queryKey: ["chat"], queryFn: context => {
    signal = context.signal;
    return new Promise<string>(() => {});
  } }).catch(() => undefined);
  identity.user = { id: "B" }; identity.token = "token-B";
  render();
  await pending;
  expect(signal.aborted).toBe(true);
  expect(previous.getQueryCache().getAll()).toHaveLength(0);
  expect(client).not.toBe(previous);
  expect(client.getQueryData(["room", "same-room"])).toBeUndefined();
  client.setQueryData(["replays"], "B_PRIVATE");
  const second = client;
  identity.user = null; identity.token = ""; render();
  expect(second.getQueryCache().getAll()).toHaveLength(0);
  expect(client.getQueryData(["replays"])).toBeUndefined();
});
