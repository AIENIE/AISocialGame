import { gameplayApi, gameApi, personaApi, roomApi } from "@/services/api";
import {
  AchievementDefinition,
  FriendItem,
  FriendRequestItem,
  GameState,
  PlayerAchievement,
  ReplayArchive,
  ReplayEvent,
  Room,
} from "@/types";

const FRIEND_STORE_KEY = "aisocial_v2_friends";
const ACHIEVEMENT_STORE_KEY = "aisocial_v2_achievements";
const REPLAY_STORE_KEY = "aisocial_v2_replays";

type FriendStore = Record<string, { friends: FriendItem[]; requests: FriendRequestItem[] }>;
type AchievementState = Record<
  string,
  {
    list: PlayerAchievement[];
    wins: number;
    games: number;
    streak: number;
  }
>;
type ReplayState = Record<string, ReplayArchive[]>;

const loadJson = <T,>(key: string, fallback: T): T => {
  try {
    const text = localStorage.getItem(key);
    if (!text) return fallback;
    return JSON.parse(text) as T;
  } catch {
    return fallback;
  }
};

const saveJson = (key: string, value: unknown) => {
  localStorage.setItem(key, JSON.stringify(value));
};

const pickAvatar = (seed: string) => `https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(seed)}`;

const candidatePool = (): FriendItem[] => [
  { id: "user-luna", displayName: "月影Luna", avatar: pickAvatar("luna"), online: true, currentGameId: "werewolf" },
  { id: "user-wei", displayName: "逻辑阿维", avatar: pickAvatar("wei"), online: true, currentGameId: "undercover" },
  { id: "user-momo", displayName: "桃子Momo", avatar: pickAvatar("momo"), online: false },
  { id: "user-k", displayName: "推理K", avatar: pickAvatar("k"), online: true },
  { id: "user-lin", displayName: "林林", avatar: pickAvatar("lin"), online: false },
];

const ensureFriendBucket = (store: FriendStore, userKey: string) => {
  if (!store[userKey]) {
    store[userKey] = { friends: [], requests: [] };
  }
  return store[userKey];
};

const definitions: AchievementDefinition[] = [
  { code: "first_game", name: "初出茅庐", description: "完成 1 局对战", rarity: "COMMON", rewardCoins: 20, target: 1 },
  { code: "ten_games", name: "久经沙场", description: "累计完成 10 局对战", rarity: "RARE", rewardCoins: 80, target: 10 },
  { code: "first_win", name: "首胜", description: "拿下第一场胜利", rarity: "COMMON", rewardCoins: 30, target: 1 },
  { code: "win_streak_3", name: "连胜节奏", description: "达成 3 连胜", rarity: "EPIC", rewardCoins: 120, target: 3 },
];

const ensureAchievementBucket = (store: AchievementState, userKey: string) => {
  if (!store[userKey]) {
    store[userKey] = {
      list: definitions.map((d) => ({ code: d.code, unlocked: false, progress: 0 })),
      wins: 0,
      games: 0,
      streak: 0,
    };
  }
  return store[userKey];
};

const updateAchievement = (bucket: AchievementState[string], code: string, value: number) => {
  const target = definitions.find((d) => d.code === code)?.target ?? 1;
  const item = bucket.list.find((a) => a.code === code);
  if (!item) return null;
  if (!item.unlocked) {
    item.progress = Math.min(value, target);
    if (item.progress >= target) {
      item.unlocked = true;
      item.unlockedAt = new Date().toISOString();
      return item;
    }
  }
  return null;
};

export const friendApi = {
  getPanelData(userKey: string): { friends: FriendItem[]; requests: FriendRequestItem[] } {
    const store = loadJson<FriendStore>(FRIEND_STORE_KEY, {});
    const bucket = ensureFriendBucket(store, userKey);
    saveJson(FRIEND_STORE_KEY, store);
    return { friends: bucket.friends, requests: bucket.requests };
  },

  searchCandidates(userKey: string, keyword: string): FriendItem[] {
    const normalized = keyword.trim().toLowerCase();
    const { friends, requests } = this.getPanelData(userKey);
    const blockedIds = new Set<string>([
      userKey,
      ...friends.map((f) => f.id),
      ...requests.map((r) => r.fromId),
    ]);
    return candidatePool()
      .filter((c) => !blockedIds.has(c.id))
      .filter((c) => !normalized || c.displayName.toLowerCase().includes(normalized) || c.id.toLowerCase().includes(normalized));
  },

  sendFriendRequest(userKey: string, target: FriendItem) {
    const store = loadJson<FriendStore>(FRIEND_STORE_KEY, {});
    const self = ensureFriendBucket(store, userKey);
    const peer = ensureFriendBucket(store, target.id);
    if (self.friends.some((f) => f.id === target.id)) {
      return;
    }
    if (peer.requests.some((r) => r.fromId === userKey)) {
      return;
    }
    peer.requests.unshift({
      id: `req-${Date.now()}`,
      fromId: userKey,
      fromName: userKey,
      fromAvatar: pickAvatar(userKey),
      createdAt: new Date().toISOString(),
    });
    saveJson(FRIEND_STORE_KEY, store);
  },

  respondRequest(userKey: string, requestId: string, accept: boolean) {
    const store = loadJson<FriendStore>(FRIEND_STORE_KEY, {});
    const self = ensureFriendBucket(store, userKey);
    const request = self.requests.find((r) => r.id === requestId);
    if (!request) return;
    self.requests = self.requests.filter((r) => r.id !== requestId);
    if (accept) {
      const peerBucket = ensureFriendBucket(store, request.fromId);
      const selfProfile: FriendItem = { id: userKey, displayName: userKey, avatar: pickAvatar(userKey), online: true };
      const peerProfile: FriendItem = {
        id: request.fromId,
        displayName: request.fromName,
        avatar: request.fromAvatar,
        online: true,
      };
      if (!self.friends.some((f) => f.id === request.fromId)) self.friends.unshift(peerProfile);
      if (!peerBucket.friends.some((f) => f.id === userKey)) peerBucket.friends.unshift(selfProfile);
    }
    saveJson(FRIEND_STORE_KEY, store);
  },

  removeFriend(userKey: string, friendId: string) {
    const store = loadJson<FriendStore>(FRIEND_STORE_KEY, {});
    const self = ensureFriendBucket(store, userKey);
    self.friends = self.friends.filter((f) => f.id !== friendId);
    const peer = ensureFriendBucket(store, friendId);
    peer.friends = peer.friends.filter((f) => f.id !== userKey);
    saveJson(FRIEND_STORE_KEY, store);
  },
};

export const achievementApi = {
  listDefinitions(): AchievementDefinition[] {
    return definitions;
  },

  listMyAchievements(userKey: string): PlayerAchievement[] {
    const store = loadJson<AchievementState>(ACHIEVEMENT_STORE_KEY, {});
    const bucket = ensureAchievementBucket(store, userKey);
    saveJson(ACHIEVEMENT_STORE_KEY, store);
    return bucket.list;
  },

  applySettlement(userKey: string, didWin: boolean): PlayerAchievement[] {
    const store = loadJson<AchievementState>(ACHIEVEMENT_STORE_KEY, {});
    const bucket = ensureAchievementBucket(store, userKey);
    const unlocked: PlayerAchievement[] = [];
    bucket.games += 1;
    if (didWin) {
      bucket.wins += 1;
      bucket.streak += 1;
    } else {
      bucket.streak = 0;
    }

    [updateAchievement(bucket, "first_game", bucket.games), updateAchievement(bucket, "ten_games", bucket.games)]
      .filter(Boolean)
      .forEach((item) => unlocked.push(item as PlayerAchievement));

    if (didWin) {
      [updateAchievement(bucket, "first_win", bucket.wins), updateAchievement(bucket, "win_streak_3", bucket.streak)]
        .filter(Boolean)
        .forEach((item) => unlocked.push(item as PlayerAchievement));
    }

    saveJson(ACHIEVEMENT_STORE_KEY, store);
    return unlocked;
  },
};

const ensureReplayBucket = (store: ReplayState, userKey: string) => {
  if (!store[userKey]) store[userKey] = [];
  return store[userKey];
};

export const replayApi = {
  list(userKey: string): ReplayArchive[] {
    const store = loadJson<ReplayState>(REPLAY_STORE_KEY, {});
    const list = ensureReplayBucket(store, userKey);
    return [...list].sort((a, b) => +new Date(b.createdAt) - +new Date(a.createdAt));
  },

  get(userKey: string, archiveId: string): ReplayArchive | undefined {
    return this.list(userKey).find((a) => a.id === archiveId);
  },

  save(userKey: string, archive: ReplayArchive) {
    const store = loadJson<ReplayState>(REPLAY_STORE_KEY, {});
    const list = ensureReplayBucket(store, userKey);
    if (!list.some((x) => x.id === archive.id)) {
      list.unshift(archive);
      saveJson(REPLAY_STORE_KEY, store);
    }
  },

  fromState(gameId: string, room: Room | undefined, state: GameState): ReplayArchive {
    const events: ReplayEvent[] = (state.logs || []).map((log, index) => ({
      id: `${state.roomId}-${index}`,
      type: log.type || "LOG",
      message: log.message,
      timestamp: log.time,
    }));
    return {
      id: `archive-${state.roomId}-${state.round || 0}-${(state.logs || []).length}`,
      gameId,
      roomId: state.roomId,
      roomName: room?.name || `房间 ${state.roomId}`,
      result: state.winner || "未判定",
      perspective: "PLAYER",
      createdAt: new Date().toISOString(),
      events,
    };
  },
};

export const quickMatchApi = {
  async start(gameId: string, displayName: string): Promise<{ roomId: string; playerId?: string; autoStarted: boolean }> {
    const [game, rooms] = await Promise.all([gameApi.detail(gameId), roomApi.list(gameId)]);
    let room: Room | undefined = rooms.find((r) => String(r.status).toLowerCase() === "waiting" && (r.seats?.length || 0) < r.maxPlayers);
    let autoStarted = false;

    if (!room) {
      const config = Object.fromEntries((game.configSchema || []).map((field) => [field.id, field.defaultValue]));
      room = await roomApi.create(gameId, {
        roomName: `[快速匹配] ${game.name} #${Math.floor(Math.random() * 1000)}`,
        isPrivate: false,
        commMode: "text",
        config,
      });
    }

    const joined = await roomApi.join(gameId, room.id, displayName);
    const nextPlayerId = (joined as any).selfPlayerId as string | undefined;

    try {
      const latestRoom = await roomApi.detail(gameId, room.id);
      const minNeeded = Math.max(game.minPlayers || 0, 2);
      if ((latestRoom.seats?.length || 0) < minNeeded) {
        const personas = await personaApi.list();
        let idx = 0;
        while ((latestRoom.seats?.length || 0) + idx < minNeeded && personas[idx % personas.length]) {
          await roomApi.addAi(gameId, room.id, personas[idx % personas.length].id);
          idx += 1;
        }
      }
      if (nextPlayerId) {
        await gameplayApi.start(gameId, room.id);
        autoStarted = true;
      }
    } catch {
      autoStarted = false;
    }

    return { roomId: room.id, playerId: nextPlayerId, autoStarted };
  },
};
