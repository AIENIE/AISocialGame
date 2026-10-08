import axios from "axios";
export { getApiErrorMessage, getApiErrorCode, isRecoverableGameError } from "./apiError";
import { HttpApiError } from "./apiError";
export type { ApiErrorResponse } from "./apiError";
import {
  AdminAuthResponse,
  AdminAuthPolicy,
  AdminLoginResult,
  AdminEnrollmentStart,
  AdminAiDecisionTrace,
  AdminAiPersonaMemory,
  AdminIntegrationStatus,
  AdminRedeemCode,
  AiSafetyControl,
  AiSafetyEvent,
  AiSafetySummary,
  AiChatResponse,
  AiEmbeddingsResponse,
  AiMessage,
  AiModel,
  AiOcrParams,
  AiOcrResponse,
  AuthResponse,
  CheckinResponse,
  CheckinStatusResponse,
  CommunityPost,
  ExchangeResponse,
  ExchangeHistoryRecord,
  Game,
  GameLogPage,
  GameState,
  LedgerEntry,
  PagedResponse,
  Persona,
  PlayerAction,
  PlayerStats,
  ReplayArchiveView,
  ReplayDetail,
  ReplayViewMode,
  RedeemResponse,
  RedemptionRecord,
  Room,
  SsoCallbackData,
  UsageRecord,
  User
} from "@/types";

const api = axios.create({
  // Default to nginx proxy prefix; callers should use relative paths (no leading /api)
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: 8000,
  withCredentials: true,
});

const adminExpiredListeners = new Set<() => void>();
export const subscribeAdminAuthExpired = (listener: () => void) => { adminExpiredListeners.add(listener); return () => { adminExpiredListeners.delete(listener); }; };
const authExpiredListeners = new Set<() => void>();
let sessionRequests = new AbortController();
api.interceptors.request.use(config => {
  if (!config.url?.startsWith("/admin")) config.signal = config.signal
    ? AbortSignal.any([config.signal as AbortSignal, sessionRequests.signal]) : sessionRequests.signal;
  return config;
});
export const subscribeAuthExpired = (listener: () => void) => {
  authExpiredListeners.add(listener);
  return () => { authExpiredListeners.delete(listener); };
};
const expireToken = (token: unknown) => {
  if (token && token === api.defaults.headers.common["X-Auth-Token"]) authExpiredListeners.forEach(listener => listener());
};
api.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401) {
    if (error.config?.url?.startsWith("/admin")) adminExpiredListeners.forEach(listener => listener());
    else expireToken(error.config?.headers?.["X-Auth-Token"]);
  }
  return Promise.reject(error);
});

export const setAuthToken = (token?: string) => {
  if (token !== api.defaults.headers.common["X-Auth-Token"]) {
    sessionRequests.abort();
    sessionRequests = new AbortController();
  }
  if (token) {
    api.defaults.headers.common["X-Auth-Token"] = token;
  } else {
    delete api.defaults.headers.common["X-Auth-Token"];
  }
};

export const authApi = {
  async logout(): Promise<void> {
    await api.post("/auth/logout");
  },
  async ssoCallback(payload: SsoCallbackData): Promise<AuthResponse> {
    const res = await api.post("/auth/sso-callback", payload);
    return res.data;
  },
  async me(signal?: AbortSignal): Promise<User> {
    const res = await api.get("/auth/me", { signal });
    return res.data;
  },
};

export const aiApi = {
  async listModels(): Promise<AiModel[]> {
    const res = await api.get("/ai/models");
    return res.data;
  },
  async chat(messages: AiMessage[], model?: string): Promise<AiChatResponse> {
    const res = await api.post("/ai/chat", { messages, model }, { timeout: 60_000 });
    return res.data;
  },
  async chatStream(
    messages: AiMessage[],
    model: string | undefined,
    onChunk: (chunk: string) => void,
    onDone: (result: AiChatResponse) => void,
  ): Promise<void> {
    const base = (import.meta.env.VITE_API_BASE_URL || "/api").replace(/\/$/, "");
    const token = api.defaults.headers.common["X-Auth-Token"] as string | undefined;
    const response = await fetch(`${base}/ai/chat/stream`, {
      signal: AbortSignal.any([AbortSignal.timeout(65_000), sessionRequests.signal]),
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { "X-Auth-Token": token } : {}),
      },
      body: JSON.stringify({ messages, model }),
    });
    if (!response.ok) {
      if (response.status === 401) expireToken(token);
      const data: unknown = await response.json().catch(() => undefined);
      const body = data && typeof data === "object" ? data as { code?: unknown; message?: unknown } : {};
      throw new HttpApiError(response.status, typeof body.code === "string" ? body.code : undefined,
        typeof body.message === "string" ? body.message : "流式请求失败");
    }
    if (!response.body) {
      throw new Error("流式请求失败");
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() ?? "";
      for (const line of lines) {
        if (!line.startsWith("data:")) {
          continue;
        }
        const payload = line.slice(5).trim();
        if (!payload) {
          continue;
        }
        const data = JSON.parse(payload);
        if (data.done) {
          onDone({
            content: "",
            modelKey: data.modelKey || "",
            promptTokens: data.promptTokens || 0,
            completionTokens: data.completionTokens || 0,
          });
        } else {
          onChunk(data.content || "");
        }
      }
    }
  },
  async embeddings(input: string[], model?: string, normalize = true): Promise<AiEmbeddingsResponse> {
    const res = await api.post("/ai/embeddings", { input, model, normalize }, { timeout: 60_000 });
    return res.data;
  },
  async ocr(params: AiOcrParams): Promise<AiOcrResponse> {
    const res = await api.post("/ai/ocr", params, { timeout: 60_000 });
    return res.data;
  },
};

export const walletApi = {
  async checkin(): Promise<CheckinResponse> {
    const res = await api.post("/wallet/checkin");
    return res.data;
  },
  async getCheckinStatus(): Promise<CheckinStatusResponse> {
    const res = await api.get("/wallet/checkin-status");
    return res.data;
  },
  async getBalance(): Promise<User["balance"]> {
    const res = await api.get("/wallet/balance");
    return res.data;
  },
  async getUsageRecords(page = 1, size = 20): Promise<PagedResponse<UsageRecord>> {
    const res = await api.get("/wallet/usage-records", { params: { page, size } });
    return res.data;
  },
  async getLedger(page = 1, size = 20): Promise<PagedResponse<LedgerEntry>> {
    const res = await api.get("/wallet/ledger", { params: { page, size } });
    return res.data;
  },
  async redeemCode(code: string): Promise<RedeemResponse> {
    const res = await api.post("/wallet/redeem", { code });
    return res.data;
  },
  async getRedemptionHistory(page = 1, size = 20): Promise<PagedResponse<RedemptionRecord>> {
    const res = await api.get("/wallet/redemption-history", { params: { page, size } });
    return res.data;
  },
  async exchangePublicToProject(amount: number, requestId?: string): Promise<ExchangeResponse> {
    const res = await api.post("/wallet/exchange/public-to-project", { amount, requestId });
    return res.data;
  },
  async getExchangeHistory(page = 1, size = 20): Promise<PagedResponse<ExchangeHistoryRecord>> {
    const res = await api.get("/wallet/exchange-history", { params: { page, size } });
    return res.data;
  },
};

export const gameApi = {
  async list(): Promise<Game[]> {
    const res = await api.get("/games");
    return res.data;
  },
  async detail(id: string): Promise<Game> {
    const res = await api.get(`/games/${id}`);
    return res.data;
  },
};

export const roomApi = {
  async search(roomCode: string): Promise<import("@/types").RoomEntry> {
    return (await api.get("/rooms/search", { params: { roomCode } })).data;
  },
  async entry(gameId: string, roomId: string, signal?: AbortSignal): Promise<import("@/types").RoomEntry> {
    return (await api.get(`/games/${gameId}/rooms/${roomId}/entry`, { signal })).data;
  },
  async list(gameId: string, params: { page?: number; size?: number; status?: string } = {}): Promise<PagedResponse<Room>> {
    const res = await api.get(`/games/${gameId}/rooms`, { params });
    return res.data;
  },
  async create(gameId: string, payload: Record<string, any>): Promise<Room> {
    const res = await api.post(`/games/${gameId}/rooms`, payload);
    return res.data;
  },
  async detail(gameId: string, roomId: string, signal?: AbortSignal): Promise<Room> {
    const res = await api.get(`/games/${gameId}/rooms/${roomId}`, { signal });
    return res.data;
  },
  async join(gameId: string, roomId: string, displayName: string, password?: string): Promise<Room> {
    const res = await api.post(`/games/${gameId}/rooms/${roomId}/join`, { displayName, password });
    return res.data;
  },
  async addAi(gameId: string, roomId: string, personaId: string): Promise<Room> {
    const res = await api.post(`/games/${gameId}/rooms/${roomId}/ai`, { personaId });
    return res.data;
  },
};

export const personaApi = {
  async list(): Promise<Persona[]> {
    const res = await api.get("/personas");
    return res.data;
  },
};

export const gameplayApi = {
  async logs(gameId: string, roomId: string, before?: number, size = 100, signal?: AbortSignal): Promise<GameLogPage> {
    const res = await api.get(`/games/${gameId}/rooms/${roomId}/logs`, { params: { before, size }, signal });
    return res.data;
  },
  async state(gameId: string, roomId: string, signal?: AbortSignal): Promise<GameState> {
    const res = await api.get(`/games/${gameId}/rooms/${roomId}/state`, { signal });
    return res.data;
  },
  async start(gameId: string, roomId: string): Promise<GameState> {
    const res = await api.post(`/games/${gameId}/rooms/${roomId}/start`, {});
    return res.data;
  },
  async speak(gameId: string, roomId: string, content: string): Promise<GameState> {
    const res = await api.post(`/games/${gameId}/rooms/${roomId}/speak`, { content });
    return res.data;
  },
  async vote(gameId: string, roomId: string, targetPlayerId: string, abstain = false): Promise<GameState> {
    const res = await api.post(`/games/${gameId}/rooms/${roomId}/vote`, { targetPlayerId, abstain });
    return res.data;
  },
  async nightAction(gameId: string, roomId: string, payload: { action: string; targetPlayerId?: string; useHeal?: boolean }): Promise<GameState> {
    const res = await api.post(`/games/${gameId}/rooms/${roomId}/night-action`, payload);
    return res.data;
  },
  async action(gameId: string, roomId: string, action: PlayerAction): Promise<GameState> {
    const res = await api.post(`/games/${gameId}/rooms/${roomId}/action`, action);
    return res.data;
  },
};

export const communityApi = {
  async list(): Promise<CommunityPost[]> {
    const res = await api.get("/community/posts");
    return res.data;
  },
  async create(content: string, tags: string[]): Promise<CommunityPost> {
    const res = await api.post("/community/posts", { content, tags });
    return res.data;
  },
  async like(id: string): Promise<CommunityPost> {
    const res = await api.post(`/community/posts/${id}/like`);
    return res.data;
  },
};

export const rankingApi = {
  async list(gameId = "total"): Promise<PlayerStats[]> {
    const res = await api.get("/rankings", { params: { gameId } });
    return res.data;
  },
};

export const serverReplayApi = {
  async list(params: { gameId?: string; playerId?: string; from?: string; to?: string; page?: number; size?: number } = {}): Promise<PagedResponse<ReplayArchiveView>> {
    const res = await api.get("/replays", { params });
    return res.data;
  },
  async my(params: { gameId?: string; from?: string; to?: string; page?: number; size?: number } = {}): Promise<PagedResponse<ReplayArchiveView>> {
    const res = await api.get("/replays/my", { params });
    return res.data;
  },
  async detail(archiveId: string): Promise<ReplayArchiveView> {
    const res = await api.get(`/replays/${archiveId}`);
    return res.data;
  },
  async events(archiveId: string, viewMode: ReplayViewMode = "PUBLIC", viewerPlayerId?: string): Promise<ReplayDetail> {
    const res = await api.get(`/replays/${archiveId}/events`, {
      params: { viewMode, ...(viewerPlayerId ? { viewerPlayerId } : {}) },
    });
    return res.data;
  },
};

const adminApiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: 8000,
  withCredentials: true,
});

type ProofRetryConfig = import("axios").InternalAxiosRequestConfig & { __adminProofRetried?: boolean };

adminApiClient.interceptors.response.use(undefined, async (error) => {
  const response = error?.response;
  if (response?.status === 401) adminExpiredListeners.forEach(listener => listener());
  const config = error?.config as ProofRetryConfig | undefined;
  if (response?.status !== 428 || response?.data?.code !== "ADMIN_OPERATION_PROOF_REQUIRED"
      || !response?.data?.challengeId || !config || config.__adminProofRetried) {
    throw error;
  }
  const code = window.prompt("该操作需要动态验证码二次确认。请输入 6 位 TOTP：");
  if (!code) {
    throw error;
  }
  const proofResponse = await adminApiClient.post("/admin/auth/operation/verify", {
    challengeId: response.data.challengeId,
    code,
  });
  config.__adminProofRetried = true;
  config.headers.set("X-Admin-Operation-Proof", proofResponse.data.proofToken);
  return adminApiClient.request(config);
});

export const adminApi = {
  async replayEvents(archiveId: string): Promise<ReplayDetail> {
    return (await adminApiClient.get(`/admin/replays/${encodeURIComponent(archiveId)}/events`)).data;
  },
  async policy(): Promise<AdminAuthPolicy> {
    const res = await adminApiClient.get("/admin/auth/policy");
    return res.data;
  },
  async login(username: string, password: string): Promise<AdminLoginResult> {
    const res = await adminApiClient.post("/admin/auth/login", { username, password });
    return res.data;
  },
  async me(): Promise<AdminAuthResponse> {
    const res = await adminApiClient.get("/admin/auth/me");
    return res.data;
  },
  async logout(): Promise<void> {
    await adminApiClient.post("/admin/auth/logout");
  },
  async startEnrollment(challengeId: string): Promise<AdminEnrollmentStart> {
    const res = await adminApiClient.post("/admin/auth/enrollment/start", { challengeId });
    return res.data;
  },
  async confirmEnrollment(challengeId: string, code: string): Promise<AdminLoginResult> {
    const res = await adminApiClient.post("/admin/auth/enrollment/confirm", { challengeId, code });
    return res.data;
  },
  async verifyTotp(challengeId: string, code: string): Promise<AdminLoginResult> {
    const res = await adminApiClient.post("/admin/auth/totp/verify", { challengeId, code });
    return res.data;
  },
  async verifyRecovery(challengeId: string, recoveryCode: string): Promise<AdminLoginResult> {
    const res = await adminApiClient.post("/admin/auth/recovery/verify", { challengeId, recoveryCode });
    return res.data;
  },
  async startRebind(): Promise<AdminEnrollmentStart> {
    const res = await adminApiClient.post("/admin/auth/rebind/start");
    return res.data;
  },
  async confirmRebind(challengeId: string, code: string): Promise<AdminLoginResult> {
    const res = await adminApiClient.post("/admin/auth/rebind/confirm", { challengeId, code });
    return res.data;
  },
  async dashboardSummary(): Promise<{
    localUsers: number;
    localRooms: number;
    localPosts: number;
    localGameStates: number;
    aiModels: number;
    openHighRiskSafetyEvents: number;
    safetyBlocksLast24h: number;
    safetyCostAnomaliesLast24h: number;
    activeSafetyControls: number;
  }> {
    const res = await adminApiClient.get("/admin/dashboard/summary");
    return res.data;
  },
  async integrationServices(): Promise<AdminIntegrationStatus> {
    const res = await adminApiClient.get("/admin/integration/services");
    return res.data;
  },
  async getUser(userId: number) {
    const res = await adminApiClient.get(`/admin/users/${userId}`);
    return res.data;
  },
  async banUser(userId: number, payload: { reason: string; permanent: boolean; expiresAt?: string }) {
    const res = await adminApiClient.post(`/admin/users/${userId}/ban`, payload);
    return res.data;
  },
  async unbanUser(userId: number, reason: string) {
    const res = await adminApiClient.post(`/admin/users/${userId}/unban`, { reason });
    return res.data;
  },
  async balance(userId: number) {
    const res = await adminApiClient.get("/admin/billing/balance", { params: { userId } });
    return res.data;
  },
  async ledger(userId: number, page = 1, size = 20) {
    const res = await adminApiClient.get("/admin/billing/ledger", { params: { userId, page, size } });
    return res.data;
  },
  async adjustBalance(payload: { userId: number; deltaTemp: number; deltaPermanent: number; reason: string; requestId?: string }) {
    const res = await adminApiClient.post("/admin/billing/adjust", payload);
    return res.data;
  },
  async reverseBalance(payload: { userId: number; originalRequestId: string; reason: string }) {
    const res = await adminApiClient.post("/admin/billing/reversal", payload);
    return res.data;
  },
  async migrateUserBalance(userId: number) {
    const res = await adminApiClient.post("/admin/billing/migrate-user", { userId });
    return res.data;
  },
  async migrateAllUserBalances(batchSize = 100) {
    const res = await adminApiClient.post("/admin/billing/migrate-all", { batchSize });
    return res.data;
  },
  async createRedeemCode(payload: {
    code?: string;
    tokens: number;
    creditType?: string;
    maxRedemptions?: number;
    validFrom?: string;
    validUntil?: string;
    active?: boolean;
  }): Promise<AdminRedeemCode> {
    const res = await adminApiClient.post("/admin/billing/redeem-codes", payload);
    return res.data;
  },
  async aiModels(): Promise<AiModel[]> {
    const res = await adminApiClient.get("/admin/ai/models");
    return res.data;
  },
  async testChat(payload: { userId?: number; sessionId?: string; model?: string; messages: AiMessage[] }): Promise<AiChatResponse> {
    const res = await adminApiClient.post("/admin/ai/test-chat", payload);
    return res.data;
  },
  async aiDecisionTraces(params: {
    roomId?: string;
    gameId?: string;
    personaId?: string;
    action?: string;
    fallback?: boolean;
    qualityFlag?: string;
    page?: number;
    size?: number;
  } = {}): Promise<PagedResponse<AdminAiDecisionTrace>> {
    const res = await adminApiClient.get("/admin/ai/decision-traces", { params });
    return res.data;
  },
  async aiPersonaMemories(personaId?: string): Promise<AdminAiPersonaMemory[]> {
    const res = await adminApiClient.get("/admin/ai/persona-memories", { params: personaId ? { personaId } : undefined });
    return res.data;
  },
  async resetAiPersonaMemory(id: number): Promise<void> {
    await adminApiClient.post(`/admin/ai/persona-memories/${id}/reset`);
  },
  async reviewAiPersonaMemory(id: number, status: "APPROVED" | "REJECTED", summary: string): Promise<void> {
    await adminApiClient.post(`/admin/ai/persona-memories/${id}/review`, { status, summary });
  },
  async safetySummary(): Promise<AiSafetySummary> {
    const res = await adminApiClient.get("/admin/safety/summary");
    return res.data;
  },
  async safetyEvents(params: {
    status?: string;
    severity?: string;
    source?: string;
    roomId?: string;
    userId?: string;
    personaId?: string;
    modelKey?: string;
    page?: number;
    size?: number;
  } = {}): Promise<PagedResponse<AiSafetyEvent>> {
    const res = await adminApiClient.get("/admin/safety/events", { params });
    return res.data;
  },
  async safetyEventDetail(id: number): Promise<AiSafetyEvent> {
    const res = await adminApiClient.get(`/admin/safety/events/${id}`);
    return res.data;
  },
  async ackSafetyEvent(id: number): Promise<AiSafetyEvent> {
    const res = await adminApiClient.post(`/admin/safety/events/${id}/ack`);
    return res.data;
  },
  async closeSafetyEvent(id: number, reason = ""): Promise<AiSafetyEvent> {
    const res = await adminApiClient.post(`/admin/safety/events/${id}/close`, { reason });
    return res.data;
  },
  async safetyControls(): Promise<AiSafetyControl[]> {
    const res = await adminApiClient.get("/admin/safety/controls");
    return res.data;
  },
  async createSafetyControl(payload: { scope: string; targetKey: string; action?: string; reason?: string; expiresAt?: string }): Promise<AiSafetyControl> {
    const res = await adminApiClient.post("/admin/safety/controls", payload);
    return res.data;
  },
  async disableSafetyControl(id: number): Promise<void> {
    await adminApiClient.delete(`/admin/safety/controls/${id}`);
  },
};

export default api;
