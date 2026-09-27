package com.aisocialgame.service;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.NightActionRequest;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.engine.GameEngine;
import com.aisocialgame.engine.GameEngineRegistry;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
public class GamePlayService {
    private final GameEngineRegistry engineRegistry;
    private final RoomService roomService;
    private final RoomAccessPolicy access;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aisocialgame.service.v2.V2GameService v2;

    public GamePlayService(GameEngineRegistry engineRegistry, RoomService roomService, RoomAccessPolicy access) {
        this.access = access;
        this.engineRegistry = engineRegistry;
        this.roomService = roomService;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public GameStateResponse state(String gameId, String roomId, User user) {
        access.requireRead(roomId, user == null ? null : user.getId());
        if (v2 != null && (v2.isV2(roomId) || (v2.newGamesEnabled() && !v2.hasState(roomId)))) return v2.state(gameId, roomId, user);
        return engine(gameId).state(roomId, user);
    }

    public GameStateResponse start(String gameId, String roomId, User user) {
        if (v2 != null && v2.newGamesEnabled()) return v2.start(gameId, roomId, user);
        Room room = roomService.getRoom(roomId);
        var validation = engine(gameId).validateStart(room);
        if (!validation.valid()) {
            throw new com.aisocialgame.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, validation.message());
        }
        return engine(gameId).start(roomId, user);
    }

    public GameStateResponse speak(String gameId, String roomId, SpeakRequest request, User user) {
        if (v2 != null && v2.isV2(roomId)) return v2.action(gameId, roomId, user, com.aisocialgame.engine.v2.RuleSupport.action("SPEAK", request.getContent(), null));
        return engine(gameId).speak(roomId, request, user);
    }

    public GameStateResponse vote(String gameId, String roomId, VoteRequest request, User user) {
        if (v2 != null && v2.isV2(roomId)) {
            PlayerAction action = com.aisocialgame.engine.v2.RuleSupport.action("VOTE", null, request.getTargetPlayerId()); action.setAbstain(request.isAbstain());
            return v2.action(gameId, roomId, user, action);
        }
        return engine(gameId).vote(roomId, request, user);
    }

    public GameStateResponse nightAction(String gameId, String roomId, NightActionRequest request, User user) {
        if (v2 != null && v2.isV2(roomId)) {
            PlayerAction action = com.aisocialgame.engine.v2.RuleSupport.action("NIGHT_ACTION", null, request.getTargetPlayerId()); action.setNightAction(request.getAction()); action.setUseHeal(request.isUseHeal());
            return v2.action(gameId, roomId, user, action);
        }
        return engine(gameId).nightAction(roomId, request, user);
    }

    public GameStateResponse action(String gameId, String roomId, PlayerAction action, User user) {
        if (v2 != null && v2.isV2(roomId)) return v2.action(gameId, roomId, user, action);
        return engine(gameId).action(roomId, action, user);
    }

    private GameEngine engine(String gameId) {
        return engineRegistry.require(gameId);
    }
}
