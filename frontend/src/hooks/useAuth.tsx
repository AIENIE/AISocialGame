import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { authApi, setAuthToken, subscribeAuthExpired } from "@/services/api";
import type { AuthResponse, SsoCallbackData, User } from "@/types";
import { dataTexts } from "@/i18n/dataTexts";

import { LOCAL_TOKEN_KEY, LOCAL_SSO_STATE_KEY, LOCAL_RETURN_TO_KEY, readStorage, writeStorage, safeReturnTo } from "./authStorage";

type AuthStatus = "checking" | "authenticated" | "anonymous" | "error";
interface AuthContextValue {
  user: User | null; token: string | null; loading: boolean; status: AuthStatus;
  redirectToSsoLogin: (returnTo?: string) => Promise<void>;
  redirectToSsoRegister: (returnTo?: string) => Promise<void>;
  ssoCallback: (payload: SsoCallbackData) => Promise<void>;
  refreshUser: () => Promise<void>;
  updateBalance: (balance: NonNullable<User["balance"]> | undefined) => void;
  logout: () => Promise<void>; displayName: string; avatar: string;
}
const AuthContext = createContext<AuthContextValue | null>(null);
export const AuthProvider = ({ children }: { children: React.ReactNode }) => {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(() => readStorage(LOCAL_TOKEN_KEY));
  const [status, setStatus] = useState<AuthStatus>(token ? "checking" : "anonymous");
  const tokenRef = useRef(token);
  const generation = useRef(0);
  const clearSession = useCallback(() => {
    generation.current += 1; tokenRef.current = null; setAuthToken(undefined);
    writeStorage(LOCAL_TOKEN_KEY, null); setToken(null); setUser(null); setStatus("anonymous");
  }, []);
  useEffect(() => subscribeAuthExpired(clearSession), [clearSession]);
  const checkSession = useCallback(async (signal?: AbortSignal) => {
    const currentToken = tokenRef.current;
    if (!currentToken) { setStatus("anonymous"); return; }
    const revision = ++generation.current;
    setStatus("checking"); setUser(null); setAuthToken(currentToken);
    try {
      const me = await authApi.me(signal);
      if (generation.current === revision && !signal?.aborted) { setUser(me); setStatus("authenticated"); }
    } catch {
      if (generation.current !== revision || signal?.aborted) return;
      setUser(null); setStatus("error");
    }
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    tokenRef.current = token; setAuthToken(token || undefined);
    if (token) void checkSession(controller.signal);
    return () => controller.abort();
  }, [token, checkSession]);
  const applyAuthResponse = (res: AuthResponse) => {
    generation.current += 1; tokenRef.current = res.token; setAuthToken(res.token);
    writeStorage(LOCAL_TOKEN_KEY, res.token); setToken(res.token); setUser(res.user); setStatus("authenticated");
  };
  const ssoCallback = async (payload: SsoCallbackData) => {
    const revision = ++generation.current; setUser(null); setStatus("checking");
    try { const res = await authApi.ssoCallback(payload); if (generation.current === revision) applyAuthResponse(res); }
    catch (error) { if (generation.current === revision) setStatus(tokenRef.current ? "error" : "anonymous"); throw error; }
  };
  const updateBalance = useCallback((balance: NonNullable<User["balance"]> | undefined) => setUser(prev => prev ? {
    ...prev, balance, balanceAvailable: !!balance, coins: balance ? balance.projectPermanentTokens + balance.projectTempTokens : undefined,
  } : prev), []);
  const redirect = async (entry: "login" | "register", returnTo?: string) => {
    const bytes = new Uint8Array(24); window.crypto.getRandomValues(bytes);
    const state = Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
    sessionStorage.setItem(LOCAL_SSO_STATE_KEY, state);
    sessionStorage.setItem(LOCAL_RETURN_TO_KEY, safeReturnTo(returnTo || `${window.location.pathname}${window.location.search}${window.location.hash}`));
    const base = (import.meta.env.VITE_API_BASE_URL || "/api").replace(/\/$/, "");
    window.location.assign(`${base}/auth/sso/${entry}?state=${encodeURIComponent(state)}`);
  };
  const logout = async () => {
    const currentToken = tokenRef.current;
    try { await authApi.logout(); if (tokenRef.current === currentToken) clearSession(); }
    catch {
      const language = (() => { try { return localStorage.getItem("aienie.user.locale.v1"); } catch { return null; } })();
      toast.error(dataTexts[language === "en" || language === "zh-TW" ? language : "zh-CN"]["auth.logoutFailed"]);
    }
  };
  return <AuthContext.Provider value={{ user, token, status, loading: status === "checking", redirectToSsoLogin: path => redirect("login", path),
    redirectToSsoRegister: path => redirect("register", path), ssoCallback, refreshUser: checkSession, updateBalance, logout,
    displayName: user?.nickname || user?.username || "", avatar: user?.avatar || "" }}>{children}</AuthContext.Provider>;
};
export const useAuth = () => { const ctx = useContext(AuthContext); if (!ctx) throw new Error("useAuth must be used within AuthProvider"); return ctx; };
