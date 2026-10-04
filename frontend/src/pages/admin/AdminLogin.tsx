import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useSensitivePageLifecycle } from "@/hooks/useSensitivePageLifecycle";
import { useAdminAuth } from "@/hooks/useAdminAuth";
import { adminApi, getApiErrorMessage } from "@/services/api";
import { AdminAuthPolicy, AdminEnrollmentStart } from "@/types";
import RecoveryCodeList from "@/components/admin/RecoveryCodeList";
import { toast } from "sonner";

type Stage = "PASSWORD" | "TOTP" | "ENROLL" | "RECOVERY" | "REBIND" | "CODES";

const AdminLogin = () => {
  const navigate = useNavigate();
  const auth = useAdminAuth();
  const { startRebind } = auth;
  const [policy, setPolicy] = useState<AdminAuthPolicy | null>(null);
  const [stage, setStage] = useState<Stage>("PASSWORD");
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [challengeId, setChallengeId] = useState("");
  const [enrollment, setEnrollment] = useState<AdminEnrollmentStart | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [passwordRecovery, setPasswordRecovery] = useState(false);
  const [loading, setLoading] = useState(false);

  const { begin, current, invalidate, version } = useSensitivePageLifecycle(() => {
    setPassword(""); setCode(""); setChallengeId(""); setEnrollment(null); setRecoveryCodes([]);
    setStage("PASSWORD"); setPasswordRecovery(false); setLoading(false);
  });

  useEffect(() => {
    const request = begin();
    void adminApi.policy().then(value => { if (current(request)) setPolicy(value); }).catch(() => { if (current(request)) setPolicy(null); });
  }, [begin, current, version]);

  useEffect(() => {
    if (!auth.loading && auth.admin?.sessionScope === "RECOVERY_REBIND_ONLY" && stage === "PASSWORD") {
      const request = begin();
      if (!current(request)) return;
      let active = true;
      void startRebind().then(next => {
        if (!active || !current(request)) return;
        setEnrollment(next); setChallengeId(next.challengeId); setStage("REBIND");
      }).catch(() => { if (active && current(request)) toast.error("恢复授权已过期，请重新登录"); });
      return () => { active = false; };
    }
  }, [auth.loading, auth.admin?.sessionScope, stage, startRebind, begin, current, version]);

  const run = async (action: (request: number) => Promise<void>) => {
    const request = begin();
    if (request === null) return;
    setLoading(true);
    try {
      await action(request);
    } catch (error) {
      if (current(request)) toast.error(getApiErrorMessage(error, "管理员认证失败"));
    } finally {
      if (current(request)) setLoading(false);
    }
  };

  const submitPassword = () => run(async request => {
    const result = passwordRecovery ? await adminApi.recoveryChallenge(username, password) : await auth.login(username, password, () => current(request));
    if (!current(request)) return;
    setPassword("");
    if (result.state === "AUTHENTICATED") { navigate("/admin"); return; }
    if (!result.challengeId) throw new Error("登录 challenge 缺失");
    setChallengeId(result.challengeId); setCode("");
    if (result.state === "ENROLLMENT_REQUIRED") {
      const next = await auth.startEnrollment(result.challengeId);
      if (!current(request)) return;
      setEnrollment(next); setStage("ENROLL");
    } else { setStage(passwordRecovery ? "RECOVERY" : "TOTP"); }
  });

  const submitTotp = () => run(async request => {
    const result = await auth.verifyTotp(challengeId, code, () => current(request));
    if (current(request) && result.state === "AUTHENTICATED") navigate("/admin");
  });

  const submitEnrollment = () => run(async request => {
    const result = await auth.confirmEnrollment(challengeId, code, () => current(request));
    if (!current(request)) return;
    setRecoveryCodes(result.recoveryCodes ?? []);
    setEnrollment(null); setCode(""); setChallengeId(""); setStage("CODES");
  });

  const submitRecovery = () => run(async request => {
    const result = await auth.verifyRecovery(challengeId, code, () => current(request));
    if (!current(request)) return;
    if (result.sessionScope !== "RECOVERY_REBIND_ONLY") throw new Error("恢复会话创建失败");
    const next = await auth.startRebind();
    if (!current(request)) return;
    setEnrollment(next); setChallengeId(next.challengeId); setCode(""); setStage("REBIND");
  });

  const submitRebind = () => run(async request => {
    const result = await auth.confirmRebind(challengeId, code, () => current(request));
    if (!current(request)) return;
    setCode(""); setEnrollment(null); setRecoveryCodes([]);
    if (result.state === "AUTHENTICATED") navigate("/admin/security");
  });

  const cancel = async () => {
    invalidate();
    await auth.logout().catch(() => undefined);
  };

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (stage === "PASSWORD") void submitPassword();
    else if (stage === "TOTP") void submitTotp();
    else if (stage === "ENROLL") void submitEnrollment();
    else if (stage === "RECOVERY") void submitRecovery();
    else if (stage === "REBIND") void submitRebind();
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100 px-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>AISocialGame 管理台</CardTitle>
          <CardDescription>
            {policy ? `${policy.env} / ${policy.authMode}` : "正在读取认证策略"}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {stage === "CODES" ? (
            <div className="space-y-4">
              <RecoveryCodeList codes={recoveryCodes} onClose={() => { invalidate(); navigate("/admin/security"); }} />
            </div>
          ) : (
            <form className="space-y-4" onSubmit={submit}>
              {stage === "PASSWORD" ? <>
                <div className="space-y-2"><Label htmlFor="username">账号</Label><Input id="username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required /></div>
                <div className="space-y-2"><Label htmlFor="password">密码</Label><Input id="password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required /></div>
              </> : <>
                {(stage === "ENROLL" || stage === "REBIND") && enrollment && <div className="space-y-2 rounded border p-3 text-sm"><p>请在验证器中添加以下密钥：</p><code className="break-all">{enrollment.manualKey}</code></div>}
                <div className="space-y-2"><Label htmlFor="code">{stage === "RECOVERY" ? "紧急码" : "6 位动态验证码"}</Label><Input id="code" inputMode={stage === "RECOVERY" ? "text" : "numeric"} autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} required /></div>
              </>}
              <Button type="submit" className="w-full" disabled={loading}>{loading ? "验证中..." : stage === "PASSWORD" ? "继续" : "验证"}</Button>
              {stage === "PASSWORD" && policy?.authMode === "password" && <Button type="button" variant="ghost" onClick={() => { const next = !passwordRecovery; invalidate(); setPasswordRecovery(next); }}>{passwordRecovery ? "返回密码登录" : "丢失动态码，使用紧急码"}</Button>}
              {stage === "TOTP" && <Button type="button" variant="ghost" className="w-full" onClick={() => { setCode(""); setStage("RECOVERY"); }} disabled={loading}>丢失动态码，使用紧急码</Button>}
              {stage !== "PASSWORD" && <Button type="button" variant="outline" className="w-full" onClick={() => void cancel()}>取消并重新登录</Button>}
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  );
};

export default AdminLogin;
