import { useEffect, useState } from "react";
import { adminApi, getApiErrorMessage } from "@/services/api";
import { useSensitivePageLifecycle } from "@/hooks/useSensitivePageLifecycle";
import { useAdminAuth } from "@/hooks/useAdminAuth";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import RecoveryCodeList from "@/components/admin/RecoveryCodeList";
import { toast } from "sonner";

export default function AdminSecurity() {
  const { admin } = useAdminAuth();
  const [remaining, setRemaining] = useState(admin?.recoveryCodesRemaining ?? 0);
  const [code, setCode] = useState("");
  const [codes, setCodes] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const { begin, current, invalidate } = useSensitivePageLifecycle(() => { setCodes([]); setCode(""); setBusy(false); });
  useEffect(() => {
    const request = begin();
    void adminApi.me().then(value => { if (current(request)) setRemaining(value.recoveryCodesRemaining ?? 0); }).catch(() => undefined);
  }, [begin, current]);
  const getCodes = async () => {
    if (busy) return;
    const request = begin();
    if (!current(request)) return;
    setBusy(true);
    setCodes([]);
    try {
      const result = await adminApi.getRecoveryCodes(code);
      if (!current(request)) return;
      setCodes(result.recoveryCodes);
      setRemaining(result.remaining);
      toast.success(`已补充 ${result.generatedCount} 个紧急码`);
    } catch (error) { if (current(request)) toast.error(getApiErrorMessage(error, "获取紧急码失败")); }
    finally { if (current(request)) { setCode(""); setBusy(false); } }
  };
  return <div className="space-y-4">
    <h2 className="text-lg font-semibold">认证安全</h2>
    <p>剩余紧急码：<span aria-label="剩余紧急码">{remaining}</span> / 10</p>
    <p className="text-sm text-slate-600">获取时保留未使用的紧急码，仅补充已失效的数量。请先完成动态码绑定。</p>
    {admin?.authMode !== "password" && <div className="space-y-2"><Label htmlFor="emergency-totp">当前动态码</Label><Input id="emergency-totp" value={code} inputMode="numeric" autoComplete="one-time-code" onChange={event => setCode(event.target.value)} /></div>}
    <Button disabled={busy} onClick={() => void getCodes()}>{busy ? "获取中…" : "获取紧急码"}</Button>
    {codes.length > 0 && <RecoveryCodeList codes={codes} onClose={invalidate} />}
  </div>;
}
