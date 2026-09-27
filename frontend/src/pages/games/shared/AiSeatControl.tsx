import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { PersonaPicker, PersonaPickerProps } from "./PersonaPicker";

interface AiSeatControlProps extends PersonaPickerProps { seatCount: number; maxPlayers?: number }
export function AiSeatControl({ seatCount, maxPlayers, ...props }: AiSeatControlProps) {
  const { t } = useTranslation();
  return <Card className="space-y-3 p-3">
    <div className="flex items-center justify-between text-sm"><span>{t("game.aiSeatTitle")}</span>
      <Badge data-testid="game-ai-seat-count">{seatCount}/{maxPlayers}</Badge></div>
    <PersonaPicker {...props} full={maxPlayers !== undefined && seatCount >= maxPlayers} />
  </Card>;
}
