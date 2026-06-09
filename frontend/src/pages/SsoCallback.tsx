import { useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { LoaderCircle } from "lucide-react";
import { LOCAL_SSO_STATE_KEY, LOCAL_TOKEN_KEY, useAuth } from "@/hooks/useAuth";
import { toast } from "sonner";

const SsoCallback = () => {
  const navigate = useNavigate();
  const { ssoCallback } = useAuth();
  const processedRef = useRef(false);

  useEffect(() => {
    if (processedRef.current) {
      return;
    }
    processedRef.current = true;

    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    const state = params.get("state");
    const hasToken = !!sessionStorage.getItem(LOCAL_TOKEN_KEY);
    const expectedState = sessionStorage.getItem(LOCAL_SSO_STATE_KEY);
    if (expectedState) {
      sessionStorage.removeItem(LOCAL_SSO_STATE_KEY);
    }

    // Ignore stale callback tabs if user already logged in and callback payload is incomplete.
    if (hasToken && (!code || !state || !expectedState)) {
      navigate("/", { replace: true });
      return;
    }

    if (!expectedState || !state || expectedState !== state) {
      toast.error("SSO 状态校验失败，请重新登录");
      navigate("/", { replace: true });
      return;
    }

    if (!code) {
      toast.error("SSO 回调参数不完整，请重新登录");
      navigate("/", { replace: true });
      return;
    }

    const redirectUrl = new URL(`${window.location.origin}${window.location.pathname}${window.location.search}`);
    redirectUrl.searchParams.delete("code");
    redirectUrl.searchParams.delete("state");

    ssoCallback({
      code,
      redirect: redirectUrl.toString(),
    })
      .then(() => {
        window.history.replaceState(null, "", `${window.location.pathname}${redirectUrl.search}`);
        navigate("/", { replace: true });
      })
      .catch((error: any) => {
        const message = error?.response?.data?.message || "登录失败，请重试";
        toast.error(message);
        navigate("/", { replace: true });
      });
  }, [navigate, ssoCallback]);

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="rounded-xl border bg-white px-8 py-6 shadow-sm flex items-center gap-3 text-slate-700">
        <LoaderCircle className="h-5 w-5 animate-spin" />
        <span>SSO 登录处理中...</span>
      </div>
    </div>
  );
};

export default SsoCallback;
