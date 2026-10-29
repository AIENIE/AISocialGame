import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { gameApi } from "@/services/api";
import { useQuickMatch } from "@/hooks/useQuickMatch";
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
  const { user, redirectToSsoLogin } = useAuth();
  const gamesQuery = useQuery<Game[]>({ queryKey: ["games"], queryFn: gameApi.list });
  const games = gamesQuery.error ? [] : gamesQuery.data || [];
  const activeGames = games.filter((g) => String(g.status).toLowerCase() === "active" && ["undercover", "werewolf", "turtle_soup"].includes(g.id));
  const [gameId, setGameId] = useState<string>("");
  const { matching, start: startMatch } = useQuickMatch();

  const start = async () => {
    if (!gameId) {
      toast.error(t("quickMatch.noGame"));
      return;
    }
    if (!user) {
      await redirectToSsoLogin();
      return;
    }
    await startMatch(gameId, displayName, () => onOpenChange(false));
  };

  return (
    <Dialog open={open} onOpenChange={value => { if (!matching) onOpenChange(value); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("quickMatch.title")}</DialogTitle>
          <DialogDescription>{t("quickMatch.desc")}</DialogDescription>
        </DialogHeader>
        {(gamesQuery.isPending || gamesQuery.error || !activeGames.length) && <DataState loading={gamesQuery.isPending} error={gamesQuery.error} empty onRetry={() => void gamesQuery.refetch()} />}
        <div className="space-y-2">
          <Select value={gameId} onValueChange={setGameId} disabled={matching}>
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
          <Button variant="outline" disabled={matching} onClick={() => onOpenChange(false)}>
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
