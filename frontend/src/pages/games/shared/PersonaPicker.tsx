import { useEffect, useId, useRef } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { Persona } from "@/types";

const builtin = new Set(["ai1", "ai2", "ai3", "ai4"]);
export function personaText(persona: Persona, field: string, fallback: string, t: TFunction) {
  return builtin.has(persona.id) && (persona.presetVersion || 1) === 1
    ? t(`persona.${persona.id}.${field}`, { defaultValue: fallback }) : fallback;
}

export function PersonaBadge({ personaId, personas = [] }: { personaId?: string; personas?: Persona[] }) {
  const { t } = useTranslation();
  const persona = personas.find(p => p.id === personaId);
  const name = persona ? personaText(persona, "name", persona.name, t)
    : personaId && builtin.has(personaId) ? t(`persona.${personaId}.name`) : t("persona.default");
  return <Badge data-testid="persona-badge" variant="outline" className="mt-1 whitespace-normal text-center text-[10px]">{t("persona.badge", { name })}</Badge>;
}

export interface PersonaPickerProps {
  personas: Persona[];
  selectedAiId: string;
  onSelectedAiIdChange: (id: string) => void;
  canAddAi: boolean;
  onAddAi: () => void;
  isLoading?: boolean;
  isError?: boolean;
  onRetry?: () => void;
  isAdding?: boolean;
  addError?: boolean;
  isHost?: boolean;
  isWaiting?: boolean;
  full?: boolean;
}

export function PersonaPicker({ personas, selectedAiId, onSelectedAiIdChange, canAddAi, onAddAi,
  isLoading = false, isError = false, onRetry, isAdding = false, addError = false,
  isHost = true, isWaiting = true, full = false }: PersonaPickerProps) {
  const { t } = useTranslation();
  const group = useId();
  const submitted = useRef(false);
  useEffect(() => { if (!isAdding) submitted.current = false; }, [isAdding, addError]);
  const disabled = !canAddAi || !personas.some(p => p.id === selectedAiId) || !isHost || !isWaiting || full || isAdding || isLoading || isError;
  return <div className="space-y-3">
    <p className="text-xs leading-relaxed text-muted-foreground">{t("persona.intro")}</p>
    {isLoading ? <p role="status">{t("persona.loading")}</p> : isError ? <div role="alert" className="space-y-2 text-sm">
      <p>{t("persona.loadFailed")}</p><Button variant="outline" onClick={onRetry}>{t("persona.retry")}</Button>
    </div> : !personas.length ? <p role="status">{t("persona.empty")}</p> : <fieldset disabled={isAdding} className="space-y-2">
      <legend className="sr-only">{t("persona.choose")}</legend>
      {personas.map(persona => <div key={persona.id} className={`rounded-md border p-3 ${selectedAiId === persona.id ? "border-primary bg-primary/5" : "border-border"}`}>
        <label className="flex cursor-pointer items-center gap-3">
          <input type="radio" name={group} value={persona.id} checked={selectedAiId === persona.id} onChange={() => onSelectedAiIdChange(persona.id)} className="accent-primary" />
          <Avatar className="h-9 w-9"><AvatarImage src={persona.avatar} alt="" /><AvatarFallback>{persona.name[0]}</AvatarFallback></Avatar>
          <span className="min-w-0"><span className="block text-sm font-medium">{personaText(persona, "name", persona.name, t)}</span>
            <span className="block text-xs leading-relaxed text-muted-foreground">{personaText(persona, "trait", persona.trait, t)}</span></span>
        </label>
        <details className="mt-2 text-xs">
          <summary className="cursor-pointer py-1 text-muted-foreground">{t("persona.details")}</summary>
          <dl className="mt-2 space-y-2 border-t pt-2 leading-relaxed">
            {["speech", "strategy", "questioning", "stance", "revision", "commitment"].map(field => {
              const fallback = field === "speech" ? persona.speechStyle : field === "strategy" ? persona.strategyStyle : persona.behaviorGuide?.[field as keyof NonNullable<Persona["behaviorGuide"]>];
              return <div key={field}><dt className="font-medium">{t(`persona.label.${field}`)}</dt><dd className="text-muted-foreground">{personaText(persona, field, fallback || t("persona.unavailable"), t)}</dd></div>;
            })}
          </dl>
        </details>
      </div>)}
    </fieldset>}
    {!isHost ? <p className="text-xs text-muted-foreground">{t("persona.hostOnly")}</p> : !isWaiting ? <p className="text-xs text-muted-foreground">{t("persona.waitingOnly")}</p> : full ? <p role="status" className="text-xs text-muted-foreground">{t("persona.full")}</p> : null}
    {addError && <p role="alert" className="text-sm text-destructive">{t("persona.addFailed")}</p>}
    <Button data-testid="game-add-ai-btn" variant="secondary" disabled={disabled} onClick={() => {
      if (disabled || submitted.current) return;
      submitted.current = true;
      onAddAi();
    }}>{t(isAdding ? "persona.adding" : "game.addAi")}</Button>
  </div>;
}
