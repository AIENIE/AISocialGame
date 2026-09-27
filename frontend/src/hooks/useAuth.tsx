import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { authApi, setAuthToken } from "@/services/api";
import { AuthResponse, SsoCallbackData, User } from "@/types";

interface AuthContextValue {
  user: User | null;
  token: string | null;
  loading: boolean;
  redirectToSsoLogin: () => Promise<void>;
  redirectToSsoRegister: () => Promise<void>;
  ssoCallback: (payload: SsoCallbackData) => Promise<void>;
  refreshUser: () => Promise<void>;
  updateBalance: (balance: NonNullable<User["balance"]>) => void;
  logout: () => Promise<void>;
  displayName: string;
  avatar: string;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export const LOCAL_TOKEN_KEY = "aisocialgame_token";
export const LOCAL_SSO_STATE_KEY = "aisocialgame_sso_state";
const LOCAL_GUEST_KEY = "aisocialgame_guest_name";
const readStorage = (kind: "localStorage" | "sessionStorage", key: string) => {
  try { return window[kind].getItem(key); } catch { return null; }
};
const writeStorage = (kind: "localStorage" | "sessionStorage", key: string, value: string | null) => {
  try { if (value === null) window[kind].removeItem(key); else window[kind].setItem(key, value); } catch { /* This feature stays in memory when storage is denied. */ }
};

const generateSsoState = () => {
  const bytes = new Uint8Array(24);
  if (window.crypto?.getRandomValues) {
    window.crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
};

const buildSsoEntryUrl = (entry: "login" | "register", state: string) => {
  const apiBase = (import.meta.env.VITE_API_BASE_URL || "/api").replace(/\/$/, "");
  return `${apiBase}/auth/sso/${entry}?state=${encodeURIComponent(state)}`;
};

export const AuthProvider = ({ children }: { children: React.ReactNode }) => {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(() => readStorage("sessionStorage", LOCAL_TOKEN_KEY));
  const [loading, setLoading] = useState<boolean>(!!token);

  useEffect(() => {
    let current = true;
    setAuthToken(token || undefined);
    if (token) {
      authApi.me().then((value) => { if (current) setUser(value); })
        .catch(() => { if (current) setUser(null); })
        .finally(() => { if (current) setLoading(false); });
    }
    return () => { current = false; };
  }, [token]);

  const applyAuthResponse = (res: AuthResponse) => {
    writeStorage("sessionStorage", LOCAL_TOKEN_KEY, res.token);
    setToken(res.token);
    setUser(res.user);
  };

  const ssoCallback = async (payload: SsoCallbackData) => {
    setLoading(true);
    try {
      const res: AuthResponse = await authApi.ssoCallback(payload);
      applyAuthResponse(res);
    } finally {
      setLoading(false);
    }
  };

  const refreshUser = async () => {
    if (!token) {
      return;
    }
    const me = await authApi.me();
    setUser(me);
  };

  const updateBalance = (balance: NonNullable<User["balance"]>) => {
    setUser((prev) => {
      if (!prev) {
        return prev;
      }
      return {
        ...prev,
        coins: balance.totalTokens,
        balance,
      };
    });
  };

  const redirectToSsoLogin = async () => {
    const state = generateSsoState();
    sessionStorage.setItem(LOCAL_SSO_STATE_KEY, state);
    window.location.assign(buildSsoEntryUrl("login", state));
  };

  const redirectToSsoRegister = async () => {
    const state = generateSsoState();
    sessionStorage.setItem(LOCAL_SSO_STATE_KEY, state);
    window.location.assign(buildSsoEntryUrl("register", state));
  };

  const logout = async () => {
    try { await authApi.logout(); }
    catch { toast.error("注销失败，请重试"); return; }
    writeStorage("sessionStorage", LOCAL_TOKEN_KEY, null);
    setUser(null);
    setToken(null);
    setAuthToken(undefined);
  };

  const displayName = useMemo(() => {
    if (user?.nickname) return user.nickname;
    const cached = readStorage("localStorage", LOCAL_GUEST_KEY);
    if (cached) return cached;
    const guest = `游客${Math.floor(Math.random() * 9000 + 1000)}`;
    writeStorage("localStorage", LOCAL_GUEST_KEY, guest);
    return guest;
  }, [user]);

  const avatar = useMemo(() => {
    if (user?.avatar) return user.avatar;
    const name = displayName || "guest";
    return `https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(name)}`;
  }, [user, displayName]);

  const value: AuthContextValue = {
    user,
    token,
    loading,
    redirectToSsoLogin,
    redirectToSsoRegister,
    ssoCallback,
    refreshUser,
    updateBalance,
    logout,
    displayName,
    avatar,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export const useAuth = () => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
};
