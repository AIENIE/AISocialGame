import { gameplayApi, gameApi, personaApi, roomApi } from "@/services/api";
import i18n from "@/i18n/config";
import { gameName } from "@/i18n/gameTexts";
import {
  AchievementDefinition,
  FriendItem,
  FriendRequestItem,
  GameState,
  PlayerAchievement,
  ReplayArchive,
  ReplayDetail,
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
    processedArchiveIds?: string[];
  }
>;
type ReplayState = Record<string, ReplayArchive[]>;

const unavailableStores = new Set<string>();
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === "object" && !Array.isArray(value);
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === "string");
const count = (value: unknown): value is number => typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
const optionalText = (value: unknown) => value === undefined || typeof value === "string";
const friend = (v: unknown): v is FriendItem => record(v) && typeof v.id === "string" && typeof v.displayName === "string"
  && typeof v.online === "boolean" && optionalText(v.avatar) && optionalText(v.currentGameId) && optionalText(v.currentRoomId);
const friendRequest = (v: unknown): v is FriendRequestItem => record(v) && [v.id, v.fromId, v.fromName, v.createdAt].every(x => typeof x === "string") && optionalText(v.fromAvatar);
const achievement = (v: unknown): v is PlayerAchievement => record(v) && typeof v.code === "string" && typeof v.unlocked === "boolean" && count(v.progress) && optionalText(v.unlockedAt);
const friendsValid = (v: unknown): v is FriendStore => record(v) && Object.values(v).every(b => record(b) && Array.isArray(b.friends) && b.friends.every(friend) && Array.isArray(b.requests) && b.requests.every(friendRequest));
const achievementsValid = (v: unknown): v is AchievementState => record(v) && Object.values(v).every(b => record(b) && Array.isArray(b.list) && b.list.every(achievement)
  && count(b.games) && count(b.wins) && count(b.streak) && (b.processedArchiveIds === undefined || strings(b.processedArchiveIds)));
const replayValid = (v: unknown): v is ReplayArchive => record(v) && [v.id, v.gameId, v.roomId, v.roomName, v.result, v.createdAt].every(x => typeof x === "string")
  && ["GOD", "PLAYER"].includes(String(v.perspective)) && Array.isArray(v.events) && v.events.every(e => record(e) && [e.id, e.timestamp, e.type, e.message].every(x => typeof x === "string"));
const replaysValid = (v: unknown): v is ReplayState => record(v) && Object.values(v).every(b => Array.isArray(b) && b.every(replayValid));

const loadJson = <T,>(key: string, fallback: T, valid: (value: unknown) => value is T): T => {
  try {
    const text = localStorage.getItem(key);
    const value: unknown = text ? JSON.parse(text) : fallback;
    if (!valid(value)) throw new Error("Invalid local data shape");
    unavailableStores.delete(key);
    return value;
  } catch {
    unavailableStores.add(key);
    return fallback;
  }
};

const saveJson = (key: string, value: unknown): boolean => {
  // Do not replace unreadable historical records with an empty fallback.
  if (unavailableStores.has(key)) return false;
  try { localStorage.setItem(key, JSON.stringify(value)); return true; }
  catch { return false; }
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
  if (!Object.prototype.hasOwnProperty.call(store, userKey)) {
    store[userKey] = { friends: [], requests: [] };
  }
  return store[userKey];
};

/**
 * 稳定 code → t key 映射：mock 成就展示文案随当前语言渲染（调用时取翻译）。
 * name/description 为展示文案（用户可见），code 为持久化标识，不翻译。
 */
const ACHIEVEMENT_DEF_KEYS: Record<string, { name: string; desc: string }> = {
  first_game: { name: "v2.achievement.first_game.name", desc: "v2.achievement.first_game.desc" },
  ten_games: { name: "v2.achievement.ten_games.name", desc: "v2.achievement.ten_games.desc" },
  first_win: { name: "v2.achievement.first_win.name", desc: "v2.achievement.first_win.desc" },
  win_streak_3: { name: "v2.achievement.win_streak_3.name", desc: "v2.achievement.win_streak_3.desc" },
};

const definitions: AchievementDefinition[] = [
  { code: "first_game", name: "初出茅庐", description: "完成 1 局对战", rarity: "COMMON", rewardCoins: 20, target: 1 },
  { code: "ten_games", name: "久经沙场", description: "累计完成 10 局对战", rarity: "RARE", rewardCoins: 80, target: 10 },
  { code: "first_win", name: "首胜", description: "拿下第一场胜利", rarity: "COMMON", rewardCoins: 30, target: 1 },
  { code: "win_streak_3", name: "连胜节奏", description: "达成 3 连胜", rarity: "EPIC", rewardCoins: 120, target: 3 },
];

const translateDefinition = (def: AchievementDefinition): AchievementDefinition => {
  const keys = ACHIEVEMENT_DEF_KEYS[def.code];
  if (!keys) return def;
  return { ...def, name: i18n.t(keys.name), description: i18n.t(keys.desc) };
};

const ensureAchievementBucket = (store: AchievementState, userKey: string) => {
  if (!Object.prototype.hasOwnProperty.call(store, userKey)) {
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
    const store = loadJson(FRIEND_STORE_KEY, {}, friendsValid);
    const bucket = ensureFriendBucket(store, userKey);
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
    const store = loadJson(FRIEND_STORE_KEY, {}, friendsValid);
    const self = ensureFriendBucket(store, userKey);
    const peer = ensureFriendBucket(store, target.id);
    if (self.friends.some((f) => f.id === target.id)) {
      return true;
    }
    if (peer.requests.some((r) => r.fromId === userKey)) {
      return true;
    }
    peer.requests.unshift({
      id: `req-${Date.now()}`,
      fromId: userKey,
      fromName: userKey,
      fromAvatar: pickAvatar(userKey),
      createdAt: new Date().toISOString(),
    });
    return saveJson(FRIEND_STORE_KEY, store);
  },

  respondRequest(userKey: string, requestId: string, accept: boolean) {
    const store = loadJson(FRIEND_STORE_KEY, {}, friendsValid);
    const self = ensureFriendBucket(store, userKey);
    const request = self.requests.find((r) => r.id === requestId);
    if (!request) return true;
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
    return saveJson(FRIEND_STORE_KEY, store);
  },

  removeFriend(userKey: string, friendId: string) {
    const store = loadJson(FRIEND_STORE_KEY, {}, friendsValid);
    const self = ensureFriendBucket(store, userKey);
    self.friends = self.friends.filter((f) => f.id !== friendId);
    const peer = ensureFriendBucket(store, friendId);
    peer.friends = peer.friends.filter((f) => f.id !== userKey);
    return saveJson(FRIEND_STORE_KEY, store);
  },
};

export const achievementApi = {
  listDefinitions(): AchievementDefinition[] {
    return definitions.map(translateDefinition);
  },

  listMyAchievements(userKey: string): PlayerAchievement[] {
    const store = loadJson(ACHIEVEMENT_STORE_KEY, {}, achievementsValid);
    const bucket = ensureAchievementBucket(store, userKey);
    return bucket.list;
  },

  async applySettlement(userKey: string, archiveId: string, didWin: boolean): Promise<{ saved: boolean; unlocked: PlayerAchievement[] }> {
    if (!archiveId || !navigator.locks) return { saved: false, unlocked: [] };
    return navigator.locks.request(`aisocial-achievements:${userKey}`, () => {
    const store = loadJson(ACHIEVEMENT_STORE_KEY, {}, achievementsValid);
    const bucket = ensureAchievementBucket(store, userKey);
    const unlocked: PlayerAchievement[] = [];
    bucket.processedArchiveIds ??= [];
    if (bucket.processedArchiveIds.includes(archiveId)) return { saved: true, unlocked };
    bucket.processedArchiveIds.push(archiveId);
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

    const saved = saveJson(ACHIEVEMENT_STORE_KEY, store);
    return { saved, unlocked: saved ? unlocked : [] };
    });
  },
};

const ensureReplayBucket = (store: ReplayState, userKey: string) => {
  if (!Object.prototype.hasOwnProperty.call(store, userKey)) store[userKey] = [];
  return store[userKey];
};

export const replayApi = {
  list(userKey: string): ReplayArchive[] {
    const store = loadJson(REPLAY_STORE_KEY, {}, replaysValid);
    const list = ensureReplayBucket(store, userKey);
    return [...list].sort((a, b) => +new Date(b.createdAt) - +new Date(a.createdAt));
  },

  get(userKey: string, archiveId: string): ReplayArchive | undefined {
    return this.list(userKey).find((a) => a.id === archiveId);
  },

  save(userKey: string, archive: ReplayArchive) {
    const store = loadJson(REPLAY_STORE_KEY, {}, replaysValid);
    const list = ensureReplayBucket(store, userKey);
    if (!list.some((x) => x.id === archive.id)) {
      list.unshift(archive);
      return saveJson(REPLAY_STORE_KEY, store);
    }
    return true;
  },

  fromState(gameId: string, room: Room | undefined, state: GameState): ReplayArchive {
    const events: ReplayEvent[] = (state.logs || []).map((log, index) => ({
      id: `${state.roomId}-${index}`,
      type: log.type || "LOG",
      message: log.message,
      timestamp: log.time,
    }));
    return {
      id: typeof state.extra?.archiveId === "string" ? state.extra.archiveId : `archive-${state.roomId}-${state.round || 0}-${(state.logs || []).length}`,
      gameId,
      roomId: state.roomId,
      roomName: room?.name || i18n.t("v2.replay.roomName", { id: state.roomId }),
      result: state.winner || i18n.t("common.undetermined"),
      perspective: "PLAYER",
      createdAt: new Date().toISOString(),
      events,
    };
  },

  fromServerDetail(detail: ReplayDetail): ReplayArchive {
    return {
      id: detail.archive.id,
      gameId: detail.archive.gameId,
      roomId: detail.archive.roomId,
      roomName: detail.archive.roomName,
      result: detail.archive.winner || i18n.t("common.undetermined"),
      perspective: "PLAYER",
      createdAt: detail.archive.finishedAt || detail.archive.createdAt || new Date().toISOString(),
      events: detail.events.map(event => ({
        id: String(event.id),
        type: event.eventType,
        message: String(event.data?.message || event.data?.content || event.eventType),
        timestamp: event.occurredAt || new Date().toISOString(),
        phase: event.phase,
        seq: event.seq,
      })),
    };
  },
};

export const quickMatchApi = {
  async start(gameId: string, displayName: string): Promise<{ roomId: string; playerId?: string; autoStarted: boolean }> {
    const [game, roomPage] = await Promise.all([gameApi.detail(gameId), roomApi.list(gameId, { page: 1, size: 30, status: "WAITING" })]);
    let room: Room | undefined = roomPage.items.find((r) => String(r.status).toLowerCase() === "waiting" && (r.seatCount ?? r.seats?.length ?? 0) < r.maxPlayers);
    let autoStarted = false;

    if (!room) {
      const config = Object.fromEntries((game.configSchema || []).map((field) => [field.id, field.defaultValue]));
      room = await roomApi.create(gameId, {
        roomName: i18n.t("v2.quickMatch.roomName", {
          name: gameName(game.id, game.name),
          seq: Math.floor(Math.random() * 1000),
        }),
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
