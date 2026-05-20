import { useEffect, useState } from "react";
import { adminApi } from "@/services/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { toast } from "sonner";

interface Summary {
  localUsers: number;
  localRooms: number;
  localPosts: number;
  localGameStates: number;
  aiModels: number;
  openHighRiskSafetyEvents: number;
  safetyBlocksLast24h: number;
  safetyCostAnomaliesLast24h: number;
  activeSafetyControls: number;
}

const Dashboard = () => {
  const [summary, setSummary] = useState<Summary | null>(null);

  useEffect(() => {
    adminApi.dashboardSummary()
      .then(setSummary)
      .catch((error) => toast.error(error?.response?.data?.message || "加载仪表盘失败"));
  }, []);

  const items = [
    { label: "本地用户", value: summary?.localUsers ?? 0 },
    { label: "房间数", value: summary?.localRooms ?? 0 },
    { label: "社区帖子", value: summary?.localPosts ?? 0 },
    { label: "进行中状态", value: summary?.localGameStates ?? 0 },
    { label: "可用模型", value: summary?.aiModels ?? 0 },
    { label: "未处理高危", value: summary?.openHighRiskSafetyEvents ?? 0 },
    { label: "24h 拦截", value: summary?.safetyBlocksLast24h ?? 0 },
    { label: "活跃安全控制", value: summary?.activeSafetyControls ?? 0 },
  ];

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold">运营概览</h2>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {items.map((item) => (
          <Card key={item.label}>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm text-slate-500">{item.label}</CardTitle>
            </CardHeader>
            <CardContent className="text-2xl font-bold">{item.value}</CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
};

export default Dashboard;
