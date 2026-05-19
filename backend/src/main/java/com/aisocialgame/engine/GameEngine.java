package com.aisocialgame.engine;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.NightActionRequest;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.model.Game;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;

import java.util.List;

public interface GameEngine {
    String gameId();

    Game metadata();

    default List<PhaseDefinition> phaseDefinitions() {
        return List.of();
    }

    default List<RoleDefinition> roleDefinitions() {
        return List.of();
    }

    ValidationResult validateStart(Room room);

    GameStateResponse state(String roomId, User user);

    GameStateResponse start(String roomId, User user);

    GameStateResponse speak(String roomId, SpeakRequest request, User user);

    GameStateResponse vote(String roomId, VoteRequest request, User user);

    GameStateResponse nightAction(String roomId, NightActionRequest request, User user);

    default GameStateResponse action(String roomId, PlayerAction action, User user) {
        String type = action != null ? action.getType() : null;
        if ("SPEAK".equalsIgnoreCase(type)) {
            SpeakRequest request = new SpeakRequest();
            request.setContent(action.getContent());
            return speak(roomId, request, user);
        }
        if ("VOTE".equalsIgnoreCase(type)) {
            VoteRequest request = new VoteRequest();
            request.setTargetPlayerId(action.getTargetPlayerId());
            request.setAbstain(action.isAbstain());
            return vote(roomId, request, user);
        }
        if ("NIGHT_ACTION".equalsIgnoreCase(type)) {
            NightActionRequest request = new NightActionRequest();
            request.setAction(action.getNightAction());
            request.setTargetPlayerId(action.getTargetPlayerId());
            request.setUseHeal(action.isUseHeal());
            return nightAction(roomId, request, user);
        }
        throw new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "未知游戏动作");
    }
}
