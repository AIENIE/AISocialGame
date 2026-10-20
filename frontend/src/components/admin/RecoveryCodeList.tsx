import { Button } from "@/components/ui/button";
import { toast } from "sonner";

export default function RecoveryCodeList({ codes, onClose }: { codes: string[]; onClose: () => void }) {
  const download = () => {
    const url = URL.createObjectURL(new Blob([codes.join("\n") + "\n"], { type: "text/plain;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "AISocialGame-admin-emergency-codes.txt";
    link.click();
    URL.revokeObjectURL(url);
  };
  return <div className="space-y-3">
    <p>紧急码可用于丢失动态码时重新绑定验证器。请保存到受保护的位置，每个码仅能使用一次。</p>
    <pre aria-label="紧急码列表" className="max-h-80 overflow-auto rounded bg-slate-950 p-3 text-sm text-white">{codes.join("\n")}</pre>
    <div className="flex flex-wrap gap-2">
      <Button variant="outline" onClick={() => void navigator.clipboard.writeText(codes.join("\n")).then(() => toast.success("已复制紧急码")).catch(() => toast.error("复制失败"))}>复制</Button>
      <Button variant="outline" onClick={download}>下载</Button>
      <Button onClick={onClose}>关闭紧急码</Button>
    </div>
  </div>;
}
