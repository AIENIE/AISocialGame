import { gameplayApi, gameApi, personaApi, roomApi } from "@/services/api";
import i18n from "@/i18n/config";
import { gameName } from "@/i18n/gameTexts";
import type { Room } from "@/types";
import { getApiErrorMessage } from "./apiError";

export interface QuickMatchResult {
  roomId: string;
  playerId?: string;
  autoStarted: boolean;
  failure?: string;
}

export const quickMatchApi = {
  async start(gameId: string, displayName: string): Promise<QuickMatchResult> {
    const [game, roomPage] = await Promise.all([gameApi.detail(gameId), roomApi.list(gameId, { page: 1, size: 30, status: "WAITING" })]);
    let room: Room | undefined = roomPage.items.find((r) => String(r.status).toLowerCase() === "waiting" && (r.seatCount ?? r.seats?.length ?? 0) < r.maxPlayers);

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

    const result: QuickMatchResult = { roomId: room.id, playerId: room.selfPlayerId, autoStarted: false };
    try {
      const joined = await roomApi.join(gameId, room.id, displayName);
      result.playerId = joined.selfPlayerId;
      let latestRoom = await roomApi.detail(gameId, room.id);
      const isHost = !!result.playerId && (latestRoom.hostUserId === result.playerId
        || latestRoom.seats.some(seat => seat.playerId === result.playerId && seat.host));
      if (!isHost || String(latestRoom.status).toUpperCase() !== "WAITING") return result;
      const minNeeded = gameId === "werewolf"
        ? Number(latestRoom.config?.playerCount || latestRoom.maxPlayers)
        : Math.max(game.minPlayers || 0, 2);
      if (latestRoom.seats.length < minNeeded) {
        const personas = await personaApi.list();
        if (!personas.length) throw new Error(i18n.t("quickMatch.noPersonas"));
        const missing = minNeeded - latestRoom.seats.length;
        for (let idx = 0; idx < missing && latestRoom.seats.length < minNeeded; idx += 1) {
          latestRoom = await roomApi.addAi(gameId, room.id, personas[idx % personas.length].id);
        }
      }
      await gameplayApi.start(gameId, room.id);
      result.autoStarted = true;
    } catch (error) {
      // Once a room exists, send the user there to recover without creating another one.
      result.failure = getApiErrorMessage(error, i18n.t("index.quickMatchFailed"));
    }
    return result;
  },
};
