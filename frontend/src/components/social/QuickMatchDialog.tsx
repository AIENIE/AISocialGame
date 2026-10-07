import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { gameApi } from "@/services/api";
import { quickMatchApi } from "@/services/v2Social";
import { localizeErrorMessage } from "@/i18n/errors";
import { gameName } from "@/i18n/gameTexts";
import { Game } from "@/types";
import { DataState } from "@/components/DataState";
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
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { user, redirectToSsoLogin } = useAuth();
  const gamesQuery = useQuery<Game[]>({ queryKey: ["games"], queryFn: gameApi.list });
  const games = gamesQuery.error ? [] : gamesQuery.data || [];
  const activeGames = games.filter((g) => String(g.status).toLowerCase() === "active" && ["undercover", "werewolf", "turtle_soup"].includes(g.id));
  const [gameId, setGameId] = useState<string>("");
  const [matching, setMatching] = useState(false);

  const start = async () => {
    if (!gameId) {
      toast.error(t("quickMatch.noGame"));
      return;
    }
    if (!user) {
      await redirectToSsoLogin();
      return;
    }
    setMatching(true);
    try {
      const result = await quickMatchApi.start(gameId, displayName);
      toast.success(result.autoStarted ? t("quickMatch.successAuto") : t("quickMatch.successRoom"));
      onOpenChange(false);
      navigate(`/room/${gameId}/${result.roomId}`);
    } catch (error: any) {
      toast.error(localizeErrorMessage(error?.response?.data?.message, "index.quickMatchFailed"));
    } finally {
      setMatching(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("quickMatch.title")}</DialogTitle>
          <DialogDescription>{t("quickMatch.desc")}</DialogDescription>
        </DialogHeader>
        {(gamesQuery.isPending || gamesQuery.error || !activeGames.length) && <DataState loading={gamesQuery.isPending} error={gamesQuery.error} empty onRetry={() => void gamesQuery.refetch()} />}
        <div className="space-y-2">
          <Select value={gameId} onValueChange={setGameId}>
            <SelectTrigger>
              <SelectValue placeholder={t("quickMatch.selectGame")} />
            </SelectTrigger>
            <SelectContent>
              {activeGames.map((game) => (
                <SelectItem key={game.id} value={game.id}>
                  {gameName(game.id, game.name)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t("quickMatch.cancel")}
          </Button>
          <Button onClick={start} disabled={matching || !gameId || gamesQuery.isPending || !!gamesQuery.error || !activeGames.some(game => game.id === gameId)}>
            {matching ? t("quickMatch.matching") : t("quickMatch.start")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};
