export type AiFunctionCode = "UNDERCOVER" | "WEREWOLF" | "TURTLE_SOUP_PLAYER" | "TURTLE_SOUP_HOST";
export type ThinkingMode = "DEFAULT" | "ENABLED" | "DISABLED";
export type ThinkingEffort = "MINIMAL" | "LOW" | "MEDIUM" | "HIGH" | "XHIGH" | "MAX";

export interface CatalogModel {
  id: number;
  modelKey: string;
  displayName: string;
  provider: string;
  supportsImageInput: boolean;
  supportsStreaming: boolean;
  thinking: {
    declared: boolean;
    supportsEnable: boolean;
    supportsDisable: boolean;
    options: { effort: ThinkingEffort; label: string }[];
    defaultEnabled: boolean | null;
    defaultEffort: string | null;
  } | null;
}

export interface FunctionModelConfig {
  functionCode: AiFunctionCode;
  label: string;
  modelKey: string;
  thinkingMode: ThinkingMode;
  thinkingEffort: ThinkingEffort | null;
  version: number;
  status: "NOT_SYNCED" | "VALID" | "INVALID";
  statusMessage: string;
  inferenceEnabled: boolean;
  inferenceMessage: string;
}

export interface AiModelConfiguration {
  catalogVersion: number;
  syncedAt: string | null;
  models: CatalogModel[];
  functions: FunctionModelConfig[];
}

export interface SaveFunctionModelConfig {
  modelKey: string;
  thinkingMode: ThinkingMode;
  thinkingEffort: ThinkingEffort | null;
  catalogVersion: number;
  version: number;
}
