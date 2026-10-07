import { AdminDataState } from "@/components/admin/AdminDataState";
import { useState } from "react";
import { adminApi } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { toast } from "sonner";

const UserAdmin = () => {
  const [userId, setUserId] = useState("");
  const [reason, setReason] = useState("违反社区规范");
  const [userInfo, setUserInfo] = useState<any>(null);

  const [readError, setReadError] = useState<unknown>();
  const [reading, setReading] = useState(false);
  const queryUser = async () => {
    const id = Number(userId);
    if (!id) {
      toast.error("请输入用户 ID");
      return;
    }
    setUserInfo(null); setReadError(undefined); setReading(true);
    try {
      const result = await adminApi.getUser(id);
      setUserInfo(result);
    } catch (error: any) {
      setReadError(error);
    } finally { setReading(false); }
  };

  const ban = async () => {
    const id = Number(userId);
    if (!id) return;
    try {
      const result = await adminApi.banUser(id, { reason, permanent: true });
      setUserInfo(result);
      toast.success("封禁成功");
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "封禁失败");
    }
  };

  const unban = async () => {
    const id = Number(userId);
    if (!id) return;
    try {
      const result = await adminApi.unbanUser(id, reason);
      setUserInfo(result);
      toast.success("解封成功");
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "解封失败");
    }
  };

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold">用户管理</h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="space-y-2">
          <Label htmlFor="user-id">用户 ID</Label>
          <Input id="user-id" value={userId} onChange={(e) => setUserId(e.target.value)} placeholder="例如 10001" />
        </div>
        <div className="space-y-2">
          <Label htmlFor="reason">操作原因</Label>
          <Input id="reason" value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button onClick={queryUser}>查询</Button>
        <Button variant="destructive" onClick={ban}>封禁</Button>
        <Button variant="outline" onClick={unban}>解封</Button>
      </div>

      {(reading || readError) && <AdminDataState loading={reading} error={readError} retry={() => void queryUser()} />}
      {userInfo && (
        <Card>
          <CardHeader>
            <CardTitle>用户详情</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            <p>用户名：{userInfo.username}</p>
            <p>邮箱：{userInfo.email}</p>
            <p>封禁状态：{userInfo.banStatus?.banned ? "已封禁" : "正常"}</p>
            <p>封禁原因：{userInfo.banStatus?.reason || "-"}</p>
            <p>项目积分：{userInfo.balance?.totalTokens === undefined ? "余额不可用" : userInfo.balance.totalTokens}</p>
          </CardContent>
        </Card>
      )}
    </div>
  );
};

export default UserAdmin;
