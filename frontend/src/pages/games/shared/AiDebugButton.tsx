import { lazy, Suspense, useState } from "react";
import { Bug } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ui/dialog";

const AiDebugWorkbench = lazy(() => import("./AiDebugWorkbench"));

export function AiDebugButton(props: { gameId: string; roomId: string; roomName: string; userKey: string; instanceId?: string }) {
  const [open, setOpen] = useState(false);
  return <Dialog open={open} onOpenChange={setOpen}>
    <Button size="icon" variant="ghost" aria-label="AI 调试" title="AI 调试 · local" data-testid="ai-debug-open" onClick={() => setOpen(true)}>
      <Bug className="h-4 w-4" />
    </Button>
    {open && <Suspense fallback={null}><AiDebugWorkbench {...props} /></Suspense>}
  </Dialog>;
}
