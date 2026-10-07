import { useEffect, useState } from "react";
import { adminApi } from "@/services/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { AdminDataState } from "@/components/admin/AdminDataState";

const IntegrationAdmin = () => {
  const [data, setData] = useState<any>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const reload = async () => {
    setLoading(true); setError(undefined); setData(null);
    try {
      const response = await adminApi.integrationServices();
      setData(response);
    } catch (error: any) {
      setError(error);
    } finally { setLoading(false); }
  };

  useEffect(() => {
    reload();
  }, []);

  if (loading || error || !data) return <AdminDataState loading={loading} error={error} retry={() => void reload()} />;
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">微服务联通状态</h2>
        <Button variant="outline" onClick={reload}>刷新</Button>
      </div>
      <Card>
        <CardHeader>
          <CardTitle>状态列表</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {(data?.services || []).map((item: any) => (
            <div key={item.service} className="flex items-center justify-between rounded border p-2">
              <span>{item.service}</span>
              <span className={item.reachable ? "text-emerald-600" : "text-red-600"}>
                {item.reachable ? "可用" : "异常"} - {item.message}
              </span>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  );
};

export default IntegrationAdmin;
