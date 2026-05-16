package com.aisocialgame.service.ai;

public record AiPlayerInfo(
        String playerId,
        String displayName,
        int seatNumber,
        boolean ai,
        boolean alive,
        String role,
        String word,
        String connectionStatus
) {
}
