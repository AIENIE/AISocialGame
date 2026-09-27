package com.aisocialgame.engine.v2;

import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.engine.ValidationResult;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.service.ai.v2.GameAiAdapter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure transitions: no network, repositories, sleep, or direct WebSocket calls. */
public interface GameRuleSet extends GameAiAdapter {
    default com.aisocialgame.model.Game definition() {
        var game=new com.aisocialgame.model.Game(gameId(),gameId(),"","",List.of(),1,32,com.aisocialgame.model.GameStatus.ACTIVE,0,List.of());
        game.setRuleVersion(2);game.setEngineBacked(true);return GameConfiguration.withDifficulty(game);
    }
    /** Only publicly declared configuration enters model observations. Plugins may narrow this projection. */
    default Map<String,Object> observationRules(GameState state) {
        Set<String> keys=new java.util.HashSet<>();definition().getConfigSchema().forEach(f -> keys.add(f.getId()));
        keys.addAll(Set.of("roleCounts","deathReveal"));
        Map<String,Object> visible=new java.util.LinkedHashMap<>();
        RuleSupport.map(state.getData().get("rules")).forEach((key,value) -> {if(keys.contains(key))visible.put(key,value);});
        return visible;
    }
    ValidationResult validateStart(Room room);
    GameState initialize(Room room, LocalDateTime now);
    void apply(GameState state, String actorId, PlayerAction action, LocalDateTime now);
    void advance(GameState state, LocalDateTime now);
    default CommitmentRules.Receipt commitmentAction(GameState state, String actorId, PlayerAction action) {
        return CommitmentRules.submitted(state, actorId, action);
    }
    default CommitmentRules.Opportunity commitmentOpportunity(GameState state, String actorId, Map<String, Object> action, int round) {
        return CommitmentRules.bind(state, this, actorId, action, round);
    }
    default String commitmentExpiry(GameState state, Map<String, Object> opportunity, Map<String, Object> action) {
        return CommitmentRules.expiredReason(state, this, opportunity, action);
    }
    /** Local recovery only: discard invalid output and close just this automated opportunity. */
    default void onAiFailure(GameState state, TurnRequest turn, LocalDateTime now) {
        if (!isTurnCurrent(state, turn)) return;
        if (legalActions(state, turn.actorId()).stream().anyMatch(a -> "SKIP".equals(a.type()))) {
            apply(state, turn.actorId(), RuleSupport.action("SKIP", "", null), now);
        } else throw new IllegalStateException("Rules must provide an automated-turn recovery");
    }
    List<LegalAction> legalActions(GameState state, String actorId);
    List<TurnRequest> pendingTurns(GameState state);
    Map<String, Object> publicData(GameState state);
    Map<String, Object> privateData(GameState state, String actorId);
    /** Retain essential facts after the recent-event window, only after visibility filtering. */
    default boolean retainObservationEvent(Map<String, Object> event) { return false; }
    default String visibleRole(GameState state, GamePlayerState player, String viewerId) {
        return "SETTLEMENT".equals(state.getPhase()) || player.getPlayerId().equals(viewerId) ? player.getRole() : null;
    }
    default String visibleWord(GameState state, GamePlayerState player, String viewerId) {
        return "SETTLEMENT".equals(state.getPhase()) || player.getPlayerId().equals(viewerId) ? player.getWord() : null;
    }
    default boolean isTurnCurrent(GameState state, TurnRequest turn) {
        return pendingTurns(state).stream().anyMatch(turn::equals);
    }
    default Set<String> winningPlayerIds(GameState state) {
        return Set.copyOf(RuleSupport.strings(state.getData().get("winnerIds")));
    }
}
