import { gameplayApi, gameApi, personaApi, roomApi } from "@/services/api";
import i18n from "@/i18n/config";
import { gameName } from "@/i18n/gameTexts";
import type { Room } from "@/types";

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
    const nextPlayerId = joined.selfPlayerId;

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
