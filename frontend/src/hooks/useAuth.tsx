import { createContext, useContext, useEffect, useMemo, useState } from "react";
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
  logout: () => void;
  displayName: string;
  avatar: string;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export const LOCAL_TOKEN_KEY = "aisocialgame_token";
export const LOCAL_SSO_STATE_KEY = "aisocialgame_sso_state";
const LOCAL_GUEST_KEY = "aisocialgame_guest_name";

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
  const [token, setToken] = useState<string | null>(() => sessionStorage.getItem(LOCAL_TOKEN_KEY));
  const [loading, setLoading] = useState<boolean>(!!token);

  useEffect(() => {
    if (token) {
      setAuthToken(token);
      authApi
        .me()
        .then(setUser)
        .catch(() => {
          logout();
        })
        .finally(() => setLoading(false));
    } else {
      setAuthToken(undefined);
    }
  }, [token]);

  const applyAuthResponse = (res: AuthResponse) => {
    sessionStorage.setItem(LOCAL_TOKEN_KEY, res.token);
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

  const logout = () => {
    sessionStorage.removeItem(LOCAL_TOKEN_KEY);
    setUser(null);
    setToken(null);
    setAuthToken(undefined);
  };

  const displayName = useMemo(() => {
    if (user?.nickname) return user.nickname;
    const cached = localStorage.getItem(LOCAL_GUEST_KEY);
    if (cached) return cached;
    const guest = `游客${Math.floor(Math.random() * 9000 + 1000)}`;
    localStorage.setItem(LOCAL_GUEST_KEY, guest);
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
