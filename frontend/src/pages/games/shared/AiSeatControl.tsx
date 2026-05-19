import { Bot } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Persona } from "@/types";

interface AiSeatControlProps {
  personas: Persona[];
  selectedAiId: string;
  onSelectedAiIdChange: (id: string) => void;
  seatCount: number;
  maxPlayers?: number;
  canAddAi: boolean;
  onAddAi: () => void;
}

export function AiSeatControl({
  personas,
  selectedAiId,
  onSelectedAiIdChange,
  seatCount,
  maxPlayers,
  canAddAi,
  onAddAi,
}: AiSeatControlProps) {
  return (
    <Card className="space-y-3 p-3">
      <div className="flex items-center justify-between text-sm">
        <span>添加 AI 补位</span>
        <Badge data-testid="game-ai-seat-count">
          {seatCount}/{maxPlayers}
        </Badge>
      </div>
      <Select value={selectedAiId} onValueChange={onSelectedAiIdChange}>
        <SelectTrigger>
          <SelectValue placeholder="选择 AI 人设" />
        </SelectTrigger>
        <SelectContent>
          {personas.map((ai) => (
            <SelectItem key={ai.id} value={ai.id}>
              {ai.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Button data-testid="game-add-ai-btn" variant="secondary" onClick={onAddAi} disabled={!canAddAi}>
        <Bot className="mr-2 h-4 w-4" /> 添加 AI
      </Button>
    </Card>
  );
}
