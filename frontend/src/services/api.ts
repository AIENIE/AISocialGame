import axios from "axios";
import {
  AdminAuthResponse,
  AdminAiDecisionTrace,
  AdminAiPersonaMemory,
  AdminIntegrationStatus,
  AdminRedeemCode,
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
});

export const setAuthToken = (token?: string) => {
  if (token) {
    api.defaults.headers.common["X-Auth-Token"] = token;
  } else {
    delete api.defaults.headers.common["X-Auth-Token"];
  }
};

export const authApi = {
  async ssoCallback(payload: SsoCallbackData): Promise<AuthResponse> {
    const res = await api.post("/auth/sso-callback", payload);
    return res.data;
  },
  async me(): Promise<User> {
    const res = await api.get("/auth/me");
    return res.data;
  },
};

export const aiApi = {
  async listModels(): Promise<AiModel[]> {
    const res = await api.get("/ai/models");
    return res.data;
  },
  async chat(messages: AiMessage[], model?: string): Promise<AiChatResponse> {
    const res = await api.post("/ai/chat", { messages, model });
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
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { "X-Auth-Token": token } : {}),
      },
      body: JSON.stringify({ messages, model }),
    });
    if (!response.ok || !response.body) {
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
    const res = await api.post("/ai/embeddings", { input, model, normalize });
    return res.data;
  },
  async ocr(params: AiOcrParams): Promise<AiOcrResponse> {
    const res = await api.post("/ai/ocr", params);
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
  async list(gameId: string): Promise<Room[]> {
    const res = await api.get(`/games/${gameId}/rooms`);
    return res.data;
  },
  async create(gameId: string, payload: Record<string, any>): Promise<Room> {
    const res = await api.post(`/games/${gameId}/rooms`, payload);
    return res.data;
  },
  async detail(gameId: string, roomId: string): Promise<Room> {
    const res = await api.get(`/games/${gameId}/rooms/${roomId}`);
    return res.data;
  },
  async join(gameId: string, roomId: string, displayName: string, playerId?: string): Promise<Room> {
    const headers = playerId ? { "X-Player-Id": playerId } : undefined;
    try {
      const res = await api.post(`/games/${gameId}/rooms/${roomId}/join`, { displayName }, { headers });
      return res.data;
    } catch (error: any) {
      const status = error?.response?.status;
      if (status === 401 || status === 403) {
        const retryHeaders = { ...(headers || {}), "X-Auth-Token": "" };
        const retryRes = await api.post(`/games/${gameId}/rooms/${roomId}/join`, { displayName }, { headers: retryHeaders });
        return retryRes.data;
      }
      throw error;
    }
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
  async state(gameId: string, roomId: string, playerId?: string): Promise<GameState> {
    const res = await api.get(`/games/${gameId}/rooms/${roomId}/state`, {
      headers: playerId ? { "X-Player-Id": playerId } : undefined,
    });
    return res.data;
  },
  async start(gameId: string, roomId: string, playerId?: string): Promise<GameState> {
    const res = await api.post(
      `/games/${gameId}/rooms/${roomId}/start`,
      {},
      { headers: playerId ? { "X-Player-Id": playerId } : undefined }
    );
    return res.data;
  },
  async speak(gameId: string, roomId: string, content: string, playerId?: string): Promise<GameState> {
    const res = await api.post(
      `/games/${gameId}/rooms/${roomId}/speak`,
      { content },
      { headers: playerId ? { "X-Player-Id": playerId } : undefined }
    );
    return res.data;
  },
  async vote(gameId: string, roomId: string, targetPlayerId: string, abstain = false, playerId?: string): Promise<GameState> {
    const res = await api.post(
      `/games/${gameId}/rooms/${roomId}/vote`,
      { targetPlayerId, abstain },
      { headers: playerId ? { "X-Player-Id": playerId } : undefined }
    );
    return res.data;
  },
  async nightAction(gameId: string, roomId: string, payload: { action: string; targetPlayerId?: string; useHeal?: boolean }, playerId?: string): Promise<GameState> {
    const res = await api.post(
      `/games/${gameId}/rooms/${roomId}/night-action`,
      payload,
      { headers: playerId ? { "X-Player-Id": playerId } : undefined }
    );
    return res.data;
  },
  async action(gameId: string, roomId: string, action: PlayerAction, playerId?: string): Promise<GameState> {
    const res = await api.post(
      `/games/${gameId}/rooms/${roomId}/action`,
      action,
      { headers: playerId ? { "X-Player-Id": playerId } : undefined }
    );
    return res.data;
  },
};

export const communityApi = {
  async list(): Promise<CommunityPost[]> {
    const res = await api.get("/community/posts");
    return res.data;
  },
  async create(content: string, tags: string[], guestName?: string): Promise<CommunityPost> {
    const res = await api.post(
      "/community/posts",
      { content, tags },
      { headers: guestName ? { "X-Guest-Name": guestName } : undefined }
    );
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
  async list(params: { gameId?: string; page?: number; size?: number } = {}): Promise<PagedResponse<ReplayArchiveView>> {
    const res = await api.get("/replays", { params });
    return res.data;
  },
  async my(params: { page?: number; size?: number } = {}): Promise<PagedResponse<ReplayArchiveView>> {
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
});

export const setAdminToken = (token?: string) => {
  if (token) {
    adminApiClient.defaults.headers.common["X-Admin-Token"] = token;
  } else {
    delete adminApiClient.defaults.headers.common["X-Admin-Token"];
  }
};

export const adminApi = {
  async login(username: string, password: string): Promise<AdminAuthResponse> {
    const res = await adminApiClient.post("/admin/auth/login", { username, password });
    return res.data;
  },
  async me(): Promise<AdminAuthResponse> {
    const res = await adminApiClient.get("/admin/auth/me");
    return res.data;
  },
  async dashboardSummary(): Promise<{ localUsers: number; localRooms: number; localPosts: number; localGameStates: number; aiModels: number }> {
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
};

export default api;
