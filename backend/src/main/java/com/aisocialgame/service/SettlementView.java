package com.aisocialgame.service;

import com.aisocialgame.model.GameState;
import java.util.List;
import java.util.Map;

/** Only rule-produced results may drive client achievements; survival is not a win signal. */
public final class SettlementView {
    private SettlementView() {}
    public static void add(Map<String,Object> extra, GameState state, String viewerId) {
        extra.put("archiveId", state.getData().get("archiveId"));
        if (!"SETTLEMENT".equals(state.getPhase())) return;
        boolean participant = viewerId != null && state.getPlayers().stream().anyMatch(p -> !p.isAi() && viewerId.equals(p.getPlayerId()));
        if (state.getData().get("winnerIds") instanceof List<?> winners) {
            extra.put("mySettlement", Map.of("eligible", participant, "didWin", participant && winners.contains(viewerId)));
        }
    }
}
