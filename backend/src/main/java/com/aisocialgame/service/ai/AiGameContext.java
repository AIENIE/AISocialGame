package com.aisocialgame.service.ai;

import java.util.List;
import java.util.Map;

public record AiGameContext(
        String gameId,
        String phase,
        int round,
        String action,
        AiPlayerInfo self,
        List<AiPlayerInfo> players,
        List<String> speeches,
        List<String> votes,
        List<String> events,
        Map<String, Object> extra
) {
}
