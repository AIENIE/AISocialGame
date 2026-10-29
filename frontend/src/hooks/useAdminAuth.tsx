import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import axios from "axios";
import { useQueryClient } from "@tanstack/react-query";
import { adminApi, advanceAdminSession, subscribeAdminAuthExpired } from "@/services/api";
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
  const adminRef=useRef<AdminAuthResponse|null>(null);
  const authenticating=useRef(false);
  const resetQueries=useCallback(()=>{ advanceAdminSession(); void queryClient.cancelQueries(); queryClient.clear(); },[queryClient]);
  const applyPrincipal=useCallback((value:AdminAuthResponse|null)=>{
    const previous=adminRef.current;
    if(previous?.username!==value?.username || previous?.sessionScope!==value?.sessionScope || previous?.expiresAt!==value?.expiresAt) resetQueries();
    adminRef.current=value; setAdmin(value);
  },[resetQueries]);
  const checkSession = useCallback(async (background=false) => {
    const request = ++generation.current;
    if(!background) advanceAdminSession();
    if(!background) setLoading(true);
    setError(false);
    try {
      const value = await adminApi.me();
      if (request === generation.current) applyPrincipal(value);
    } catch (failure) {
      if (request === generation.current && !axios.isCancel(failure) && (!axios.isAxiosError(failure) || failure.response?.status !== 401)) setError(true);
    } finally { if (request === generation.current) setLoading(false); }
  }, [applyPrincipal]);
  const retry=useCallback(()=>checkSession(),[checkSession]);
  useEffect(() => {
    let hidden=false;
    const hide = () => { hidden=true; generation.current += 1; authenticating.current=false; advanceAdminSession(); setLoading(false); };
    const show = () => { if(hidden) { hidden=false; void checkSession(true); } };
    const focus = () => { if(!authenticating.current && !hidden) void checkSession(true); };
    window.addEventListener("pagehide", hide);
    window.addEventListener("pageshow", show);
    window.addEventListener("focus", focus);
    void retry();
    return () => { generation.current += 1; advanceAdminSession(); window.removeEventListener("pagehide", hide); window.removeEventListener("pageshow",show); window.removeEventListener("focus",focus); };
  }, [retry,checkSession]);
  useEffect(() => subscribeAdminAuthExpired(source => {
    if(source && source!=="/admin/auth/me" && source!=="/admin/auth/logout") {
      if(!authenticating.current) void checkSession(true);
      return;
    }
    generation.current += 1; authenticating.current=false; adminRef.current=null; setAdmin(null); setLoading(false); setError(false);
    resetQueries();
  }), [resetQueries,checkSession]);

  const authenticate = async (action: () => Promise<AdminLoginResult>, current: () => boolean = () => true) => {
    const request = ++generation.current;
    authenticating.current=true; advanceAdminSession();
    const valid = () => request === generation.current && current();
    try {
      const result = await action();
      if (valid() && result.state === "AUTHENTICATED") {
        const value = await adminApi.me();
        if (valid()) { applyPrincipal(value); setLoading(false); }
      }
      if (valid()) { setLoading(false); setError(false); }
      return result;
    } finally {
      if(request===generation.current) authenticating.current=false;
    }
  };

  const login = (username: string, password: string, current?: () => boolean) => authenticate(() => adminApi.login(username, password), current);
  const verifyTotp = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.verifyTotp(challengeId, code), current);
  const confirmEnrollment = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.confirmEnrollment(challengeId, code), current);
  const verifyRecovery = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.verifyRecovery(challengeId, code), current);
  const confirmRebind = (challengeId: string, code: string, current?: () => boolean) => authenticate(() => adminApi.confirmRebind(challengeId, code), current);

  const logout = async () => {
    const request = ++generation.current;
    authenticating.current=true; advanceAdminSession();
    window.dispatchEvent(new Event("admin-auth-reset"));
    setLoading(true);
    try {
      await adminApi.logout();
      if (request === generation.current) {
        applyPrincipal(null); resetQueries();
      }
    } catch { if (request === generation.current) toast.error("退出失败，请重试。"); }
    finally { if(request===generation.current) { authenticating.current=false; setLoading(false); } }
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
