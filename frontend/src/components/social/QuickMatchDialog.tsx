import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { gameApi } from "@/services/api";
import { quickMatchApi } from "@/services/v2Social";
import { Game } from "@/types";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { toast } from "sonner";
import { useAuth } from "@/hooks/useAuth";

interface QuickMatchDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  displayName: string;
}

export const QuickMatchDialog = ({ open, onOpenChange, displayName }: QuickMatchDialogProps) => {
  const navigate = useNavigate();
  const { user, redirectToSsoLogin } = useAuth();
  const { data: games = [] } = useQuery<Game[]>({ queryKey: ["games"], queryFn: gameApi.list });
  const activeGames = games.filter((g) => String(g.status).toLowerCase() === "active");
  const [gameId, setGameId] = useState<string>("");
  const [matching, setMatching] = useState(false);

  const start = async () => {
    if (!gameId) {
      toast.error("请选择游戏类型");
      return;
    }
    if (!user) {
      await redirectToSsoLogin();
      return;
    }
    setMatching(true);
    try {
      const result = await quickMatchApi.start(gameId, displayName);
      toast.success(result.autoStarted ? "匹配成功，已自动开局" : "匹配成功，已进入房间");
      onOpenChange(false);
      navigate(`/room/${gameId}/${result.roomId}`);
    } catch (error: any) {
      toast.error(error?.response?.data?.message || "快速匹配失败，请稍后重试");
    } finally {
      setMatching(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>快速匹配</DialogTitle>
          <DialogDescription>一键加入可用房间，必要时自动创建房间并补齐 AI。</DialogDescription>
        </DialogHeader>
        <div className="space-y-2">
          <Select value={gameId} onValueChange={setGameId}>
            <SelectTrigger>
              <SelectValue placeholder="选择要匹配的游戏" />
            </SelectTrigger>
            <SelectContent>
              {activeGames.map((game) => (
                <SelectItem key={game.id} value={game.id}>
                  {game.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={start} disabled={matching}>
            {matching ? "匹配中..." : "开始匹配"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};
