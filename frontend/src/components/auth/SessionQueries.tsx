import { useEffect, useMemo, type ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useAuth } from "@/hooks/useAuth";
export const SessionQueries = ({ children }: { children: ReactNode }) => {
  const { user, token } = useAuth();
  const identity = user ? `${user.id}:${token}` : `anonymous:${token || ""}`;
  const session = useMemo(() => ({ identity, client: new QueryClient() }), [identity]);
  const queryClient = session.client;
  useEffect(() => () => { void queryClient.cancelQueries(); queryClient.clear(); }, [queryClient]);
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
};
