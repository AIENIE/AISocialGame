import { createContext, useCallback, useContext, useEffect, useState } from "react";
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
  login: (username: string, password: string) => Promise<AdminLoginResult>;
  verifyTotp: (challengeId: string, code: string) => Promise<AdminLoginResult>;
  startEnrollment: (challengeId: string) => Promise<AdminEnrollmentStart>;
  confirmEnrollment: (challengeId: string, code: string) => Promise<AdminLoginResult>;
  verifyRecovery: (challengeId: string, code: string) => Promise<AdminLoginResult>;
  startRebind: () => Promise<AdminEnrollmentStart>;
  confirmRebind: (challengeId: string, code: string) => Promise<AdminLoginResult>;
  logout: () => Promise<void>;
}

const AdminAuthContext = createContext<AdminAuthContextValue | null>(null);

export const AdminAuthProvider = ({ children }: { children: React.ReactNode }) => {
  const [admin, setAdmin] = useState<AdminAuthResponse | null>(null);
  const [loading, setLoading] = useState(true);

  const [error, setError] = useState(false);
  const queryClient = useQueryClient();
  const retry = useCallback(async () => {
    setLoading(true); setError(false); setAdmin(null);
    try { setAdmin(await adminApi.me()); }
    catch (failure) { if (!axios.isAxiosError(failure) || failure.response?.status !== 401) setError(true); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void retry(); }, [retry]);
  useEffect(() => subscribeAdminAuthExpired(() => { setAdmin(null); void queryClient.cancelQueries(); queryClient.clear(); }), [queryClient]);

  const applyAuthenticated = async (result: AdminLoginResult) => {
    if (result.state === "AUTHENTICATED") {
      setAdmin(await adminApi.me());
    }
    return result;
  };

  const login = async (username: string, password: string) => applyAuthenticated(await adminApi.login(username, password));
  const verifyTotp = async (challengeId: string, code: string) => applyAuthenticated(await adminApi.verifyTotp(challengeId, code));
  const confirmEnrollment = async (challengeId: string, code: string) => applyAuthenticated(await adminApi.confirmEnrollment(challengeId, code));
  const verifyRecovery = async (challengeId: string, code: string) => applyAuthenticated(await adminApi.verifyRecovery(challengeId, code));
  const confirmRebind = async (challengeId: string, code: string) => applyAuthenticated(await adminApi.confirmRebind(challengeId, code));

  const logout = async () => {
    try {
      await adminApi.logout();
      setAdmin(null); void queryClient.cancelQueries(); queryClient.clear();
    } catch { toast.error("退出失败，请重试。"); }
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
