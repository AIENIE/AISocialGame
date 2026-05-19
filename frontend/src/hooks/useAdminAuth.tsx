import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { adminApi, setAdminToken } from "@/services/api";
import { AdminAuthResponse } from "@/types";

interface AdminAuthContextValue {
  admin: AdminAuthResponse | null;
  token: string | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

const AdminAuthContext = createContext<AdminAuthContextValue | null>(null);

const LOCAL_ADMIN_TOKEN_KEY = "aisocialgame_admin_token";

export const AdminAuthProvider = ({ children }: { children: React.ReactNode }) => {
  const [admin, setAdmin] = useState<AdminAuthResponse | null>(null);
  const [token, setToken] = useState<string | null>(() => sessionStorage.getItem(LOCAL_ADMIN_TOKEN_KEY));
  const [loading, setLoading] = useState<boolean>(!!token);

  useEffect(() => {
    if (!token) {
      setAdminToken(undefined);
      setLoading(false);
      return;
    }
    setAdminToken(token);
    adminApi
      .me()
      .then((res) => setAdmin(res))
      .catch(() => {
        logout();
      })
      .finally(() => setLoading(false));
  }, [token]);

  const login = async (username: string, password: string) => {
    setLoading(true);
    const res = await adminApi.login(username, password);
    sessionStorage.setItem(LOCAL_ADMIN_TOKEN_KEY, res.token);
    setToken(res.token);
    setAdmin(res);
    setLoading(false);
  };

  const logout = () => {
    sessionStorage.removeItem(LOCAL_ADMIN_TOKEN_KEY);
    setToken(null);
    setAdmin(null);
    setAdminToken(undefined);
  };

  const value = useMemo(
    () => ({
      admin,
      token,
      loading,
      login,
      logout,
    }),
    [admin, token, loading]
  );

  return <AdminAuthContext.Provider value={value}>{children}</AdminAuthContext.Provider>;
};

export const useAdminAuth = () => {
  const ctx = useContext(AdminAuthContext);
  if (!ctx) {
    throw new Error("useAdminAuth must be used within AdminAuthProvider");
  }
  return ctx;
};
