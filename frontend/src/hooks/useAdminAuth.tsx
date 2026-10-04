import { createContext, useContext, useEffect, useRef, useState } from "react";
import { adminApi } from "@/services/api";
import { AdminAuthResponse, AdminEnrollmentStart, AdminLoginResult } from "@/types";

interface AdminAuthContextValue {
  admin: AdminAuthResponse | null;
  loading: boolean;
  login: (username: string, password: string, current?: () => boolean) => Promise<AdminLoginResult>;
  verifyTotp: (challengeId: string, code: string, current?: () => boolean) => Promise<AdminLoginResult>;
  startEnrollment: (challengeId: string) => Promise<AdminEnrollmentStart>;
  confirmEnrollment: (challengeId: string, code: string, current?: () => boolean) => Promise<AdminLoginResult>;
  verifyRecovery: (challengeId: string, code: string, current?: () => boolean) => Promise<AdminLoginResult>;
  startRebind: () => Promise<AdminEnrollmentStart>;
  confirmRebind: (challengeId: string, code: string, current?: () => boolean) => Promise<AdminLoginResult>;
  logout: () => Promise<void>;
}

const AdminAuthContext = createContext<AdminAuthContextValue | null>(null);

export const AdminAuthProvider = ({ children }: { children: React.ReactNode }) => {
  const [admin, setAdmin] = useState<AdminAuthResponse | null>(null);
  const [loading, setLoading] = useState(true);

  const generation = useRef(0);
  useEffect(() => {
    let active = true;
    const request = generation.current;
    const hide = () => { generation.current += 1; setLoading(false); };
    window.addEventListener("pagehide", hide);
    void adminApi.me().then(value => { if (active && request === generation.current) setAdmin(value); })
      .catch(() => { if (active && request === generation.current) setAdmin(null); })
      .finally(() => { if (active && request === generation.current) setLoading(false); });
    return () => { active = false; generation.current += 1; window.removeEventListener("pagehide", hide); };
  }, []);

  const authenticate = async (action: () => Promise<AdminLoginResult>, current: () => boolean = () => true) => {
    const request = ++generation.current;
    const valid = () => request === generation.current && current();
    const result = await action();
    if (valid() && result.state === "AUTHENTICATED") {
      const value = await adminApi.me();
      if (valid()) { setAdmin(value); setLoading(false); }
    }
    if (valid()) setLoading(false);
    return result;
  };

  const login = (username: string, password: string, current?: () => boolean) => authenticate(() => adminApi.login(username, password), current);
  const verifyTotp = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.verifyTotp(challengeId, code), current);
  const confirmEnrollment = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.confirmEnrollment(challengeId, code), current);
  const verifyRecovery = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.verifyRecovery(challengeId, code), current);
  const confirmRebind = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.confirmRebind(challengeId, code), current);

  const logout = async () => {
    generation.current += 1;
    window.dispatchEvent(new Event("admin-auth-reset"));
    setAdmin(null);
    setLoading(false);
    await adminApi.logout();
  };

  const value = {
    admin,
    loading,
    login,
    verifyTotp,
    startEnrollment: adminApi.startEnrollment,
    confirmEnrollment,
    verifyRecovery,
    startRebind: adminApi.startRebind,
    confirmRebind,
    logout,
  };

  return <AdminAuthContext.Provider value={value}>{children}</AdminAuthContext.Provider>;
};

export const useAdminAuth = () => {
  const ctx = useContext(AdminAuthContext);
  if (!ctx) {
    throw new Error("useAdminAuth must be used within AdminAuthProvider");
  }
  return ctx;
};
