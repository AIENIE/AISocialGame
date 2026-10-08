package com.aisocialgame.dto;

import com.aisocialgame.model.Room;
import java.time.OffsetDateTime;

/** Minimal discovery data; never exposes seats, credentials, or game configuration. */
public record RoomEntryResponse(String id, String roomCode, String gameId, String name, String status,
                                boolean isPrivate, int seatCount, int maxPlayers, boolean passwordRequired,
                                boolean joined, OffsetDateTime expiresAt) {
    public RoomEntryResponse(Room room, String viewerId) {
        this(room.getId(), room.getRoomCode(), room.getGameId(), room.getName(), room.getStatus().name(),
                room.isPrivate(), room.getSeatCount(), room.getMaxPlayers(), room.isPrivate(),
                viewerId.equals(room.getHostUserId()) || room.getSeats().stream().anyMatch(s -> viewerId.equals(s.getPlayerId())),
                new RoomResponse(room).getExpiresAt());
    }
}
