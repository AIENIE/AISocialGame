import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import axios from "axios";
import { useQueryClient } from "@tanstack/react-query";
import { adminApi, subscribeAdminAuthExpired } from "@/services/api";
import { AdminAuthResponse, AdminEnrollmentStart, AdminLoginResult } from "@/types";

interface AdminAuthContextValue {
  admin: AdminAuthResponse | null;
  loading: boolean;
  error: boolean;
  retry: () => Promise<void>;
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

  const [error, setError] = useState(false);
  const queryClient = useQueryClient();
  const generation = useRef(0);
  const retry = useCallback(async () => {
    const request = ++generation.current;
    setLoading(true); setError(false); setAdmin(null);
    try {
      const value = await adminApi.me();
      if (request === generation.current) setAdmin(value);
    } catch (failure) {
      if (request === generation.current && (!axios.isAxiosError(failure) || failure.response?.status !== 401)) setError(true);
    } finally { if (request === generation.current) setLoading(false); }
  }, []);
  useEffect(() => {
    const hide = () => { generation.current += 1; setLoading(false); };
    window.addEventListener("pagehide", hide);
    void retry();
    return () => { generation.current += 1; window.removeEventListener("pagehide", hide); };
  }, [retry]);
  useEffect(() => subscribeAdminAuthExpired(() => {
    generation.current += 1; setAdmin(null); setLoading(false); setError(false);
    void queryClient.cancelQueries(); queryClient.clear();
  }), [queryClient]);

  const authenticate = async (action: () => Promise<AdminLoginResult>, current: () => boolean = () => true) => {
    const request = ++generation.current;
    const valid = () => request === generation.current && current();
    const result = await action();
    if (valid() && result.state === "AUTHENTICATED") {
      const value = await adminApi.me();
      if (valid()) { setAdmin(value); setLoading(false); }
    }
    if (valid()) { setLoading(false); setError(false); }
    return result;
  };

  const login = (username: string, password: string, current?: () => boolean) => authenticate(() => adminApi.login(username, password), current);
  const verifyTotp = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.verifyTotp(challengeId, code), current);
  const confirmEnrollment = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.confirmEnrollment(challengeId, code), current);
  const verifyRecovery = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.verifyRecovery(challengeId, code), current);
  const confirmRebind = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.confirmRebind(challengeId, code), current);

  const logout = async () => {
    const request = ++generation.current;
    window.dispatchEvent(new Event("admin-auth-reset"));
    setLoading(false);
    try {
      await adminApi.logout();
      if (request === generation.current) {
        setAdmin(null); void queryClient.cancelQueries(); queryClient.clear();
      }
    } catch { if (request === generation.current) toast.error("退出失败，请重试。"); }
  };

  const value = {
    admin,
    loading,
    error,
    retry,
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
