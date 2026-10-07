import { AdminDataState } from "@/components/admin/AdminDataState";
import { useState } from "react";
import { adminApi } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { toast } from "sonner";

const BillingAdmin = () => {
  const [userId, setUserId] = useState("");
  const [balance, setBalance] = useState<any>(null);
  const [ledger, setLedger] = useState<any>(null);
  const [adjustTemp, setAdjustTemp] = useState("0");
  const [adjustPermanent, setAdjustPermanent] = useState("0");
  const [adjustReason, setAdjustReason] = useState("");
  const [reverseRequestId, setReverseRequestId] = useState("");
  const [reverseReason, setReverseReason] = useState("");
  const [redeemCodeInput, setRedeemCodeInput] = useState("");
  const [redeemTokens, setRedeemTokens] = useState("");
  const [redeemCreditType, setRedeemCreditType] = useState("CREDIT_TYPE_PERMANENT");
  const [redeemMaxRedemptions, setRedeemMaxRedemptions] = useState("1");
  const [createdRedeemCode, setCreatedRedeemCode] = useState<any>(null);
  const [migrateAllResult, setMigrateAllResult] = useState<any>(null);

  const [readError, setReadError] = useState<unknown>();
  const [reading, setReading] = useState(false);
  const load = async () => {
    const id = Number(userId);
    if (!id) {
      toast.error("请输入用户 ID");
      return;
    }
    setBalance(null); setLedger(null); setReadError(undefined); setReading(true);
    try {
      const [balanceRes, ledgerRes] = await Promise.all([
        adminApi.balance(id),
        adminApi.ledger(id, 1, 10),
      ]);
      setBalance(balanceRes);
      setLedger(ledgerRes);
    } catch (error: any) {
      setReadError(error);
    } finally { setReading(false); }
  };

  const parseUserId = (): number | null => {
    const id = Number(userId);
    if (!id) {
      toast.error("请输入用户 ID");
      return null;
    }
    return id;
  };

  const adjustBalance = async () => {
    const id = parseUserId();
    if (!id) return;
    if (!adjustReason.trim()) {
      toast.error("请输入调整原因");
      return;
    }
    try {
      const nextBalance = await adminApi.adjustBalance({
        userId: id,
        deltaTemp: Number(adjustTemp) || 0,
        deltaPermanent: Number(adjustPermanent) || 0,
        reason: adjustReason.trim(),
      });
      setBalance(nextBalance);
      toast.success("调整成功");
      await load();
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "调整失败");
    }
  };

  const reverseBalance = async () => {
    const id = parseUserId();
    if (!id) return;
    if (!reverseRequestId.trim() || !reverseReason.trim()) {
      toast.error("请填写原始 requestId 和冲正原因");
      return;
    }
    try {
      const nextBalance = await adminApi.reverseBalance({
        userId: id,
        originalRequestId: reverseRequestId.trim(),
        reason: reverseReason.trim(),
      });
      setBalance(nextBalance);
      toast.success("冲正成功");
      await load();
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "冲正失败");
    }
  };

  const migrateBalance = async () => {
    const id = parseUserId();
    if (!id) return;
    try {
      const nextBalance = await adminApi.migrateUserBalance(id);
      setBalance(nextBalance);
      toast.success("迁移完成");
      await load();
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "迁移失败");
    }
  };

  const migrateAllBalances = async () => {
    try {
      const result = await adminApi.migrateAllUserBalances(100);
      setMigrateAllResult(result);
      toast.success(`全量迁移完成：成功 ${result.success}，失败 ${result.failed}`);
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "全量迁移失败");
    }
  };

  const createRedeemCode = async () => {
    const tokens = Number(redeemTokens);
    if (!Number.isFinite(tokens) || tokens <= 0) {
      toast.error("兑换积分必须大于 0");
      return;
    }
    const parsedMax = redeemMaxRedemptions.trim() ? Number(redeemMaxRedemptions) : undefined;
    if (parsedMax !== undefined && (!Number.isInteger(parsedMax) || parsedMax <= 0)) {
      toast.error("最大兑换次数必须为正整数");
      return;
    }
    try {
      const created = await adminApi.createRedeemCode({
        code: redeemCodeInput.trim() || undefined,
        tokens,
        creditType: redeemCreditType,
        maxRedemptions: parsedMax,
        active: true,
      });
      setCreatedRedeemCode(created);
      toast.success(`兑换码已生成：${created.code}`);
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "生成兑换码失败");
    }
  };

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold">积分管理</h2>
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-2">
          <Label htmlFor="billing-user-id">用户 ID</Label>
          <Input id="billing-user-id" value={userId} onChange={(e) => setUserId(e.target.value)} />
        </div>
        <Button onClick={load}>查询余额与流水</Button>
      </div>

      {(reading || readError) && <AdminDataState loading={reading} error={readError} retry={() => void load()} />}
      <Card>
        <CardHeader>
          <CardTitle>客服补发/扣回</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div className="space-y-1">
              <Label>临时积分增减（可负数）</Label>
              <Input value={adjustTemp} onChange={(e) => setAdjustTemp(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label>永久积分增减（可负数）</Label>
              <Input value={adjustPermanent} onChange={(e) => setAdjustPermanent(e.target.value)} />
            </div>
          </div>
          <div className="space-y-1">
            <Label>原因</Label>
            <Input value={adjustReason} onChange={(e) => setAdjustReason(e.target.value)} />
          </div>
          <Button onClick={adjustBalance}>提交调整</Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>客服冲正</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="space-y-1">
            <Label>原始 requestId</Label>
            <Input value={reverseRequestId} onChange={(e) => setReverseRequestId(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label>冲正原因</Label>
            <Input value={reverseReason} onChange={(e) => setReverseReason(e.target.value)} />
          </div>
          <Button onClick={reverseBalance}>提交冲正</Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>历史项目积分迁移</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          <p className="text-sm text-muted-foreground">从 payService 读取该用户项目积分快照并落库到本地账本（幂等）。</p>
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={migrateBalance}>迁移当前用户</Button>
            <Button variant="outline" onClick={migrateAllBalances}>全量迁移所有用户</Button>
          </div>
          {migrateAllResult && (
            <div className="rounded border p-2 text-sm">
              <p>扫描：{migrateAllResult.scanned}，成功：{migrateAllResult.success}，失败：{migrateAllResult.failed}</p>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>生成兑换码</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div className="space-y-1">
              <Label htmlFor="create-redeem-code">兑换码（可选）</Label>
              <Input
                id="create-redeem-code"
                data-testid="admin-redeem-code-input"
                value={redeemCodeInput}
                onChange={(e) => setRedeemCodeInput(e.target.value)}
                placeholder="留空自动生成"
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="create-redeem-tokens">发放积分</Label>
              <Input
                id="create-redeem-tokens"
                data-testid="admin-redeem-tokens-input"
                value={redeemTokens}
                onChange={(e) => setRedeemTokens(e.target.value)}
              />
            </div>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div className="space-y-1">
              <Label htmlFor="create-redeem-credit-type">积分类型</Label>
              <select
                id="create-redeem-credit-type"
                data-testid="admin-redeem-credit-type"
                className="h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
                value={redeemCreditType}
                onChange={(e) => setRedeemCreditType(e.target.value)}
              >
                <option value="CREDIT_TYPE_PERMANENT">永久积分</option>
                <option value="CREDIT_TYPE_TEMP">临时积分</option>
              </select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="create-redeem-max">最大兑换次数</Label>
              <Input
                id="create-redeem-max"
                data-testid="admin-redeem-max-input"
                value={redeemMaxRedemptions}
                onChange={(e) => setRedeemMaxRedemptions(e.target.value)}
                placeholder="留空不限次数"
              />
            </div>
          </div>
          <Button data-testid="admin-create-redeem-code-btn" onClick={createRedeemCode}>创建兑换码</Button>
          {createdRedeemCode && (
            <div className="rounded border p-2 text-sm" data-testid="admin-created-redeem-code">
              <p>兑换码：{createdRedeemCode.code}</p>
              <p>积分：{createdRedeemCode.tokens}（{createdRedeemCode.creditType}）</p>
            </div>
          )}
        </CardContent>
      </Card>

      {balance && (
        <Card>
          <CardHeader>
            <CardTitle>余额概览</CardTitle>
          </CardHeader>
          <CardContent className="text-sm space-y-1">
            <p>总积分：{balance.totalTokens}</p>
            <p>公共永久：{balance.publicPermanentTokens}</p>
            <p>项目临时：{balance.projectTempTokens}</p>
            <p>项目永久：{balance.projectPermanentTokens}</p>
          </CardContent>
        </Card>
      )}

      {ledger && (
        <Card>
          <CardHeader>
            <CardTitle>最近流水</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            {(ledger.entries || []).map((entry: any) => (
              <div key={entry.id} className="rounded border p-2">
                <p>类型：{entry.type} | 来源：{entry.source || "-"}</p>
                <p>变动：temp {entry.tokenDeltaTemp} / permanent {entry.tokenDeltaPermanent} / public {entry.tokenDeltaPublic}</p>
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
};

export default BillingAdmin;
