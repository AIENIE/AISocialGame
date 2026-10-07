import { Button } from "@/components/ui/button";
export function AdminDataState({ loading, error, retry }: { loading?: boolean; error?: unknown; retry?: () => void }) {
  return <div role={error ? "alert" : "status"} className="space-y-3 rounded-lg border p-6 text-sm text-slate-500"><p>{loading ? "正在读取数据…" : error ? "数据读取失败，请重试。" : "暂无数据"}</p>{!!error && retry && <Button variant="outline" onClick={retry}>重试</Button>}</div>;
}
